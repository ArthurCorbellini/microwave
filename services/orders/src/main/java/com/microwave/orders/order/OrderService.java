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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderService {

  private final OrderRepository orderRepository;
  private final CatalogClient catalogClient;
  private final PaymentsClient paymentsClient;
  private final OrderEventPublisher orderEventPublisher;
  private final ReservationCommandPublisher reservationCommandPublisher;

  public OrderService(
      OrderRepository orderRepository, CatalogClient catalogClient, PaymentsClient paymentsClient,
      OrderEventPublisher orderEventPublisher, ReservationCommandPublisher reservationCommandPublisher) {
    this.orderRepository = orderRepository;
    this.catalogClient = catalogClient;
    this.paymentsClient = paymentsClient;
    this.orderEventPublisher = orderEventPublisher;
    this.reservationCommandPublisher = reservationCommandPublisher;
  }

  // No longer calls payments synchronously — see handleInventoryReserved,
  // invoked later by InventoryReservedListener (Task 17) once the async
  // reservation step resolves. The client sees this order as CREATED
  // immediately and discovers the final outcome via GET /orders/{id}.
  public Order createOrder(Long productId, int quantity) {
    ProductResponse product = fetchProduct(productId);
    BigDecimal totalAmount = product.price().multiply(BigDecimal.valueOf(quantity));

    Order order = orderRepository.save(new Order(productId, quantity, totalAmount, OrderStatus.CREATED));

    orderEventPublisher.publishOrderCreated(order);
    reservationCommandPublisher.sendReserveStock(order.getId(), productId, quantity);

    return order;
  }

  // Called by InventoryReservedListener. The CREATED check, combined with
  // Order's @Version, makes a redelivered reply a no-op instead of re-charging
  // payments or overwriting an order that already settled.
  public void handleInventoryReserved(InventoryReservedReply reply) {
    Order order = orderRepository.findById(reply.orderId())
        .orElseThrow(() -> new OrderNotFoundException(reply.orderId()));

    if (order.getStatus() != OrderStatus.CREATED) {
      return;
    }

    if (!reply.reserved()) {
      order.updateStatus(OrderStatus.REJECTED);
      orderRepository.save(order);
      return;
    }

    PaymentResponse payment = requestPayment(order);
    order.updateStatus(payment.status() == PaymentStatus.APPROVED ? OrderStatus.CONFIRMED : OrderStatus.REJECTED);
    orderRepository.save(order);
  }

  private ProductResponse fetchProduct(Long productId) {
    try {
      return catalogClient.getProduct(productId);
    } catch (FeignException ex) {
      if (ex.status() == 404) {
        throw new ProductNotFoundException(productId);
      }
      throw new UpstreamServiceUnavailableException("catalog", ex);
    }
  }

  private PaymentResponse requestPayment(Order order) {
    try {
      return paymentsClient.charge(new PaymentRequest(order.getId(), order.getTotalAmount()));
    } catch (FeignException ex) {
      throw new UpstreamServiceUnavailableException("payments", ex);
    }
  }

  public Order findById(Long id) {
    return orderRepository.findById(id)
        .orElseThrow(() -> new OrderNotFoundException(id));
  }

  public List<Order> findAll() {
    return orderRepository.findAll();
  }
}
