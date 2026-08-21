package com.microwave.orders.payments;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.inventory.messaging.ReleaseStockCommand;
import com.microwave.orders.order.Order;
import com.microwave.orders.order.OrderRepository;
import com.microwave.orders.order.OrderStatus;
import com.microwave.orders.payments.messaging.PaymentProcessedReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
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
class PaymentProcessedListenerIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  private static final String TEST_RELEASE_STOCK_QUEUE = "test.inventory.release-stock.queue";

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @Autowired
  private OrderRepository orderRepository;

  @BeforeEach
  void bindTestReleaseStockQueue() {
    Queue queue = new Queue(TEST_RELEASE_STOCK_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(queue);
    Binding binding = BindingBuilder.bind(queue)
        .to(new DirectExchange(RabbitMQConfig.INVENTORY_EXCHANGE))
        .with(RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY);
    rabbitAdmin.declareBinding(binding);
  }

  @Test
  void confirmsOrderWhenApproved() {
    Order order = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY,
        new PaymentProcessedReply(order.getId(), true, null));

    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    });
  }

  @Test
  void rejectsOrderAndReleasesStockWhenDeclined() {
    Order order = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY,
        new PaymentProcessedReply(order.getId(), false, "AMOUNT_EXCEEDS_LIMIT"));

    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.REJECTED);
    });

    ReleaseStockCommand command =
        (ReleaseStockCommand) rabbitTemplate.receiveAndConvert(TEST_RELEASE_STOCK_QUEUE, 10000);
    assertThat(command).isNotNull();
    assertThat(command.orderId()).isEqualTo(order.getId());
  }
}
