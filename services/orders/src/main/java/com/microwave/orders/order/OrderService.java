package com.microwave.orders.order;

import com.microwave.orders.catalog.CatalogClient;
import com.microwave.orders.catalog.ProductResponse;
import com.microwave.orders.inventory.ReservationCommandPublisher;
import com.microwave.orders.inventory.messaging.InventoryReservedReply;
import com.microwave.orders.order.exceptions.OrderNotFoundException;
import com.microwave.orders.order.exceptions.ProductNotFoundException;
import com.microwave.orders.order.exceptions.UpstreamServiceUnavailableException;
import com.microwave.orders.payments.PaymentStatus;
import com.microwave.orders.payments.PaymentsClient;
import com.microwave.orders.payments.rest.PaymentRequest;
import com.microwave.orders.payments.rest.PaymentResponse;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

// Intentionally NOT @Transactional in createOrder or handleInventoryReserved:
// each method persists/updates the order first, then performs a side effect
// (publishing OrderCreated, sending ReserveStock, calling payments) that can
// fail independently. Wrapping either in a transaction would roll back what
// was already recorded whenever that follow-up call fails — see
// docs/decision-log/tech-debts.md TD-1, which documents this gap for the
// payments-unreachable case (now living in handleInventoryReserved instead
// of the original synchronous POST /orders flow).
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

  // Called by InventoryReservedListener. The CREATED check makes a sequential
  // redelivery (arriving after the first one already settled the order) a
  // no-op. @Version protects against a stale concurrent *write* — it does NOT
  // prevent a concurrent double-charge if two listener threads both read
  // CREATED before either saves; RabbitMQ's default listener concurrency of 1
  // (Task 14's RabbitMQConfig) is what actually keeps that window closed in
  // practice.
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
