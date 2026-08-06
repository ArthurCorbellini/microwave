package com.microwave.orders.order;

import com.microwave.orders.catalog.CatalogClient;
import com.microwave.orders.catalog.ProductDto;
import com.microwave.orders.payments.PaymentRequestDto;
import com.microwave.orders.payments.PaymentResponseDto;
import com.microwave.orders.payments.PaymentStatusDto;
import com.microwave.orders.payments.PaymentsClient;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        orderService = new OrderService(orderRepository, catalogClient, paymentsClient);
    }

    @Test
    void createsAndConfirmsOrderOnApprovedPayment() {
        initService();
        when(catalogClient.getProduct(1L))
                .thenReturn(new ProductDto(1L, "Keyboard", "Mechanical keyboard", new BigDecimal("100.00")));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentsClient.charge(any(PaymentRequestDto.class)))
                .thenReturn(new PaymentResponseDto(1L, 100L, new BigDecimal("200.00"), PaymentStatusDto.APPROVED));

        Order order = orderService.createOrder(1L, 2);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("200.00");
        verify(orderRepository, times(2)).save(any(Order.class));
    }

    @Test
    void rejectsOrderOnRejectedPayment() {
        initService();
        when(catalogClient.getProduct(1L))
                .thenReturn(new ProductDto(1L, "Keyboard", "Mechanical keyboard", new BigDecimal("100.00")));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentsClient.charge(any(PaymentRequestDto.class)))
                .thenReturn(new PaymentResponseDto(1L, 100L, new BigDecimal("200.00"), PaymentStatusDto.REJECTED));

        Order order = orderService.createOrder(1L, 2);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
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
    void keepsOrderCreatedWhenPaymentsUnavailable() {
        initService();
        when(catalogClient.getProduct(1L))
                .thenReturn(new ProductDto(1L, "Keyboard", "Mechanical keyboard", new BigDecimal("100.00")));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentsClient.charge(any(PaymentRequestDto.class))).thenThrow(feignErrorWithStatus(503));

        assertThatThrownBy(() -> orderService.createOrder(1L, 2))
                .isInstanceOf(UpstreamServiceUnavailableException.class);

        // The order was already persisted as CREATED before the payments call failed,
        // and it is NOT rolled back — see docs/tech-debt.md TD-1.
        verify(orderRepository, times(1)).save(any(Order.class));
    }
}
