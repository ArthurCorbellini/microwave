package com.microwave.orders.order;

import com.microwave.orders.catalog.CatalogClient;
import com.microwave.orders.catalog.ProductResponse;
import com.microwave.orders.inventory.ReservationCommandPublisher;
import com.microwave.orders.inventory.messaging.InventoryReservedReply;
import com.microwave.orders.order.exceptions.OrderNotFoundException;
import com.microwave.orders.order.exceptions.ProductNotFoundException;
import com.microwave.orders.order.exceptions.UpstreamServiceUnavailableException;
import com.microwave.orders.payments.PaymentCommandPublisher;
import com.microwave.orders.payments.messaging.PaymentProcessedReply;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

// Intentionally NOT @Transactional — persisting the order and the async
// side effects (publish/send) must not roll back together; see TD-1.
@Service
public class OrderService {

  private final OrderRepository orderRepository;
  private final CatalogClient catalogClient;
  private final OrderEventPublisher orderEventPublisher;
  private final ReservationCommandPublisher reservationCommandPublisher;
  private final PaymentCommandPublisher paymentCommandPublisher;

  public OrderService(
      OrderRepository orderRepository, CatalogClient catalogClient,
      OrderEventPublisher orderEventPublisher, ReservationCommandPublisher reservationCommandPublisher,
      PaymentCommandPublisher paymentCommandPublisher) {
    this.orderRepository = orderRepository;
    this.catalogClient = catalogClient;
    this.orderEventPublisher = orderEventPublisher;
    this.reservationCommandPublisher = reservationCommandPublisher;
    this.paymentCommandPublisher = paymentCommandPublisher;
  }

  // Returns immediately as CREATED — reservation/payment resolve async;
  // the client discovers the outcome via GET /orders/{id}.
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
  // no-op.
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

    paymentCommandPublisher.sendChargePayment(order.getId(), order.getTotalAmount());
  }

  // Called by PaymentProcessedListener. Same CREATED guard as above — Order
  // stays CREATED for the whole window between the inventory reply and this
  // one, so the guard is valid for both reply handlers without a dedicated
  // intermediate status (see the Phase 4 design spec's "Order status model").
  public void handlePaymentProcessed(PaymentProcessedReply reply) {
    Order order = orderRepository.findById(reply.orderId())
        .orElseThrow(() -> new OrderNotFoundException(reply.orderId()));

    if (order.getStatus() != OrderStatus.CREATED) {
      return;
    }

    if (reply.approved()) {
      order.updateStatus(OrderStatus.CONFIRMED);
      orderRepository.save(order);
      return;
    }

    reservationCommandPublisher.sendReleaseStock(order.getId());
    order.updateStatus(OrderStatus.REJECTED);
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

  public Order findById(Long id) {
    return orderRepository.findById(id)
        .orElseThrow(() -> new OrderNotFoundException(id));
  }

  public List<Order> findAll() {
    return orderRepository.findAll();
  }
}
