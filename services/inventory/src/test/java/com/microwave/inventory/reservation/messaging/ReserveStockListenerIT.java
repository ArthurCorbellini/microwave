package com.microwave.inventory.reservation.messaging;

import com.microwave.inventory.config.RabbitMQConfig;
import com.microwave.inventory.reservation.Reservation;
import com.microwave.inventory.reservation.ReservationRepository;
import com.microwave.inventory.stock.Stock;
import com.microwave.inventory.stock.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
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
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ReserveStockListenerIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  private static final String TEST_REPLY_QUEUE = "test.orders.inventory-reserved.queue";

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @Autowired
  private ReservationRepository reservationRepository;

  @Autowired
  private StockRepository stockRepository;

  private ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setupTestQueue() {
    // Declare a simple queue for receiving replies
    Queue testQueue = new Queue(TEST_REPLY_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(testQueue);

    // Bind the test queue to the orders exchange
    Binding testBinding = BindingBuilder.bind(testQueue)
        .to(new DirectExchange(RabbitMQConfig.ORDERS_EXCHANGE))
        .with(RabbitMQConfig.INVENTORY_RESERVED_ROUTING_KEY);
    rabbitAdmin.declareBinding(testBinding);
  }

  @Test
  void reservesStockAndRepliesWhenAvailable() throws Exception {
    stockRepository.save(new Stock(1L, 50));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RESERVE_STOCK_ROUTING_KEY,
        new ReserveStockCommand(42L, 1L, 5));

    Message message = rabbitTemplate.receive(TEST_REPLY_QUEUE, 10000);
    assertThat(message).isNotNull();
    InventoryReservedReply reply = objectMapper.readValue(message.getBody(), InventoryReservedReply.class);

    assertThat(reply).isNotNull();
    assertThat(reply.orderId()).isEqualTo(42L);
    assertThat(reply.reserved()).isTrue();

    Optional<Reservation> reservation = reservationRepository.findByOrderId(42L);
    assertThat(reservation).isPresent();
    assertThat(reservation.get().getQuantity()).isEqualTo(5);
  }

  @Test
  void repliesNotReservedWhenStockInsufficient() throws Exception {
    stockRepository.save(new Stock(2L, 1));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RESERVE_STOCK_ROUTING_KEY,
        new ReserveStockCommand(43L, 2L, 5));

    Message message = rabbitTemplate.receive(TEST_REPLY_QUEUE, 10000);
    assertThat(message).isNotNull();
    InventoryReservedReply reply = objectMapper.readValue(message.getBody(), InventoryReservedReply.class);

    assertThat(reply).isNotNull();
    assertThat(reply.orderId()).isEqualTo(43L);
    assertThat(reply.reserved()).isFalse();
    assertThat(reply.reason()).isEqualTo("OUT_OF_STOCK");
  }
}
