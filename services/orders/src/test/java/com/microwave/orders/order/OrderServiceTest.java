package com.microwave.orders.order;

import com.microwave.orders.catalog.CatalogClient;
import com.microwave.orders.catalog.dto.ProductResponse;
import com.microwave.orders.inventory.ReservationCommandPublisher;
import com.microwave.orders.inventory.messaging.InventoryReservedReply;
import com.microwave.orders.order.enums.OrderStatus;
import com.microwave.orders.order.exceptions.OrderNotFoundException;
import com.microwave.orders.order.exceptions.ProductNotFoundException;
import com.microwave.orders.order.exceptions.UpstreamServiceUnavailableException;
import com.microwave.orders.order.messaging.OrderEventPublisher;
import com.microwave.orders.payments.PaymentsClient;
import com.microwave.orders.payments.dto.PaymentRequest;
import com.microwave.orders.payments.dto.PaymentResponse;
import com.microwave.orders.payments.enums.PaymentStatus;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private CatalogClient catalogClient;

  @Mock
  private PaymentsClient paymentsClient;

  @Mock
  private OrderEventPublisher orderEventPublisher;

  @Mock
  private ReservationCommandPublisher reservationCommandPublisher;

  private OrderService orderService;

  private static FeignException feignErrorWithStatus(int status) {
    Request request = Request.create(
        Request.HttpMethod.GET, "/products/1", Map.of(), null, StandardCharsets.UTF_8, null);
    Response response = Response.builder()
        .status(status)
        .request(request)
        .headers(Map.of())
        .build();
    return FeignException.errorStatus("Client#method", response);
  }

  private void initService() {
    orderService = new OrderService(
        orderRepository, catalogClient, paymentsClient, orderEventPublisher, reservationCommandPublisher);
  }

  @Test
  void createsOrderAndPublishesEventAndCommandWithoutCallingPayments() {
    initService();
    when(catalogClient.getProduct(1L))
        .thenReturn(new ProductResponse(1L, "Keyboard", "Mechanical keyboard", new BigDecimal("100.00")));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
      Order persisted = invocation.getArgument(0);
      ReflectionTestUtils.setField(persisted, "id", 42L);
      return persisted;
    });

    Order order = orderService.createOrder(1L, 2);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    assertThat(order.getTotalAmount()).isEqualByComparingTo("200.00");
    verify(orderRepository, times(1)).save(any(Order.class));
    verify(paymentsClient, never()).charge(any(PaymentRequest.class));
    verify(orderEventPublisher).publishOrderCreated(order);
    verify(reservationCommandPublisher).sendReserveStock(42L, 1L, 2);
  }

  @Test
  void throwsProductNotFoundAndCreatesNoOrder() {
    initService();
    when(catalogClient.getProduct(1L)).thenThrow(feignErrorWithStatus(404));

    assertThatThrownBy(() -> orderService.createOrder(1L, 2))
        .isInstanceOf(ProductNotFoundException.class);
    verify(orderRepository, never()).save(any(Order.class));
  }

  @Test
  void throwsUpstreamUnavailableWhenCatalogFails() {
    initService();
    when(catalogClient.getProduct(1L)).thenThrow(feignErrorWithStatus(500));

    assertThatThrownBy(() -> orderService.createOrder(1L, 2))
        .isInstanceOf(UpstreamServiceUnavailableException.class);
    verify(orderRepository, never()).save(any(Order.class));
  }

  @Test
  void confirmsOrderWhenReservedAndPaymentApproved() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(paymentsClient.charge(any(PaymentRequest.class)))
        .thenReturn(new PaymentResponse(1L, 42L, new BigDecimal("200.00"), PaymentStatus.APPROVED));

    orderService.handleInventoryReserved(new InventoryReservedReply(42L, true, null));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    verify(paymentsClient).charge(any(PaymentRequest.class));
  }

  @Test
  void rejectsOrderWhenReservedButPaymentDeclined() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(paymentsClient.charge(any(PaymentRequest.class)))
        .thenReturn(new PaymentResponse(1L, 42L, new BigDecimal("200.00"), PaymentStatus.REJECTED));

    orderService.handleInventoryReserved(new InventoryReservedReply(42L, true, null));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
  }

  @Test
  void rejectsOrderWhenNotReservedWithoutCallingPayments() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

    orderService.handleInventoryReserved(new InventoryReservedReply(42L, false, "OUT_OF_STOCK"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
    verify(paymentsClient, never()).charge(any(PaymentRequest.class));
  }

  @Test
  void ignoresAReplyForAnOrderThatAlreadyLeftCreated() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CONFIRMED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

    orderService.handleInventoryReserved(new InventoryReservedReply(42L, true, null));

    verify(paymentsClient, never()).charge(any(PaymentRequest.class));
    verify(orderRepository, never()).save(any(Order.class));
  }

  @Test
  void throwsOrderNotFoundWhenReplyReferencesUnknownOrder() {
    initService();
    when(orderRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> orderService.handleInventoryReserved(new InventoryReservedReply(99L, true, null)))
        .isInstanceOf(OrderNotFoundException.class);
  }

  @Test
  void throwsUpstreamUnavailableWhenPaymentsFailsDuringReservedHandling() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
    when(paymentsClient.charge(any(PaymentRequest.class))).thenThrow(feignErrorWithStatus(503));

    assertThatThrownBy(() -> orderService.handleInventoryReserved(new InventoryReservedReply(42L, true, null)))
        .isInstanceOf(UpstreamServiceUnavailableException.class);

    // Order stays CREATED — not rolled back, not confirmed. TD-1 stays open
    // through Phase 3 (see docs/decision-log/tech-debts.md), though the
    // RabbitMQ retry wrapping this call (Task 14's RabbitMQConfig) does at
    // least retry the payments call 3 times before giving up, unlike Phase 1.
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
  }
}
