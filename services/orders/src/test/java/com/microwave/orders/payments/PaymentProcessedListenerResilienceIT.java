package com.microwave.orders.payments;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.order.Order;
import com.microwave.orders.order.OrderRepository;
import com.microwave.orders.order.OrderStatus;
import com.microwave.orders.payments.messaging.PaymentProcessedReply;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class PaymentProcessedListenerResilienceIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private OrderRepository orderRepository;

  @Test
  void isIdempotentForADuplicateReply() {
    Order order = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));
    PaymentProcessedReply reply = new PaymentProcessedReply(order.getId(), true, null);

    rabbitTemplate.convertAndSend(RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY, reply);
    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    });

    // A second delivery of the same reply hits the CREATED guard (the order
    // already left CREATED) and is a no-op — status stays CONFIRMED, not
    // reprocessed.
    rabbitTemplate.convertAndSend(RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY, reply);

    await().pollDelay(2, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    });
  }

  @Test
  void deadLettersAMessageThatAlwaysFailsToProcess() {
    // No Order exists for this orderId, so OrderService.handlePaymentProcessed
    // always throws OrderNotFoundException — guaranteed to exhaust all 3
    // retries and land on the dead-letter queue.
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY,
        new PaymentProcessedReply(999999L, true, null));

    PaymentProcessedReply deadLettered =
        (PaymentProcessedReply) rabbitTemplate.receiveAndConvert(RabbitMQConfig.PAYMENT_PROCESSED_DLQ, 15000);
    assertThat(deadLettered).isNotNull();
    assertThat(deadLettered.orderId()).isEqualTo(999999L);
  }
}
