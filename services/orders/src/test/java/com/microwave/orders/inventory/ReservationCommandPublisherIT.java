package com.microwave.orders.inventory;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.inventory.messaging.ReleaseStockCommand;
import com.microwave.orders.inventory.messaging.ReserveStockCommand;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ReservationCommandPublisherIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  private static final String TEST_COMMAND_QUEUE = "test.inventory.reserve-stock.queue";
  private static final String TEST_RELEASE_QUEUE = "test.inventory.release-stock.queue";

  @Autowired
  private ReservationCommandPublisher reservationCommandPublisher;

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @BeforeEach
  void bindTestCommandQueue() {
    Queue queue = new Queue(TEST_COMMAND_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(queue);
    Binding binding = BindingBuilder.bind(queue)
        .to(new DirectExchange(RabbitMQConfig.INVENTORY_EXCHANGE))
        .with(RabbitMQConfig.RESERVE_STOCK_ROUTING_KEY);
    rabbitAdmin.declareBinding(binding);

    Queue releaseQueue = new Queue(TEST_RELEASE_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(releaseQueue);
    Binding releaseBinding = BindingBuilder.bind(releaseQueue)
        .to(new DirectExchange(RabbitMQConfig.INVENTORY_EXCHANGE))
        .with(RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY);
    rabbitAdmin.declareBinding(releaseBinding);
  }

  @Test
  void publishesReserveStockCommand() {
    reservationCommandPublisher.sendReserveStock(42L, 1L, 5);

    ReserveStockCommand received =
        (ReserveStockCommand) rabbitTemplate.receiveAndConvert(TEST_COMMAND_QUEUE, 10000);

    assertThat(received).isNotNull();
    assertThat(received.orderId()).isEqualTo(42L);
    assertThat(received.productId()).isEqualTo(1L);
    assertThat(received.quantity()).isEqualTo(5);
  }

  @Test
  void publishesReleaseStockCommand() {
    reservationCommandPublisher.sendReleaseStock(77L);

    ReleaseStockCommand received =
        (ReleaseStockCommand) rabbitTemplate.receiveAndConvert(TEST_RELEASE_QUEUE, 10000);

    assertThat(received).isNotNull();
    assertThat(received.orderId()).isEqualTo(77L);
  }
}
