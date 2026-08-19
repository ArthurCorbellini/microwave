package com.microwave.inventory.reservation;

import com.microwave.inventory.config.RabbitMQConfig;
import com.microwave.inventory.reservation.messaging.InventoryReservedReply;
import com.microwave.inventory.reservation.messaging.ReserveStockCommand;
import com.microwave.inventory.stock.Stock;
import com.microwave.inventory.stock.StockRepository;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ReserveStockListenerResilienceIT {

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
  private StockRepository stockRepository;

  @BeforeEach
  void bindTestReplyQueue() {
    // autoDelete=false: the idempotency test performs two receiveAndConvert
    // calls in a row, each of which opens and cancels a consumer. With
    // autoDelete=true, the queue is removed the instant the first consumer
    // disconnects, so the second receive would fail with NOT_FOUND. The
    // queue is still scoped to this test class's Testcontainers broker, so
    // there's no real leak risk.
    Queue queue = new Queue(TEST_REPLY_QUEUE, true, false, false);
    rabbitAdmin.declareQueue(queue);
    Binding binding = BindingBuilder.bind(queue)
        .to(new DirectExchange(RabbitMQConfig.ORDERS_EXCHANGE))
        .with(RabbitMQConfig.INVENTORY_RESERVED_ROUTING_KEY);
    rabbitAdmin.declareBinding(binding);
  }

  @Test
  void isIdempotentForADuplicateCommand() {
    stockRepository.save(new Stock(3L, 50));
    ReserveStockCommand command = new ReserveStockCommand(44L, 3L, 5);

    rabbitTemplate.convertAndSend(RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RESERVE_STOCK_ROUTING_KEY, command);
    rabbitTemplate.receiveAndConvert(TEST_REPLY_QUEUE, 10000);

    rabbitTemplate.convertAndSend(RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RESERVE_STOCK_ROUTING_KEY, command);
    InventoryReservedReply secondReply =
        (InventoryReservedReply) rabbitTemplate.receiveAndConvert(TEST_REPLY_QUEUE, 10000);

    assertThat(secondReply).isNotNull();
    assertThat(secondReply.reserved()).isTrue();

    Optional<Stock> stock = stockRepository.findByProductId(3L);
    assertThat(stock).isPresent();
    // Decremented once, not twice — the second delivery hit the idempotency
    // check in ReservationService.reserve() and never touched Stock again.
    assertThat(stock.get().getAvailableQuantity()).isEqualTo(45);
  }

  @Test
  void deadLettersAMessageThatAlwaysFailsToProcess() {
    stockRepository.save(new Stock(4L, 50));

    // orderId=null violates Reservation's not-null column constraint on save,
    // which ReserveStockListener does NOT catch (only InsufficientStockException
    // is caught) — so this is guaranteed to exhaust all 3 retries and dead-letter.
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RESERVE_STOCK_ROUTING_KEY,
        new ReserveStockCommand(null, 4L, 5));

    ReserveStockCommand deadLettered =
        (ReserveStockCommand) rabbitTemplate.receiveAndConvert(RabbitMQConfig.RESERVE_STOCK_DLQ, 15000);
    assertThat(deadLettered).isNotNull();
    assertThat(deadLettered.productId()).isEqualTo(4L);

    // Each failed attempt rolled back inside its own @Transactional boundary,
    // so Stock is untouched after all 3 attempts — not decremented 3 times.
    Optional<Stock> stock = stockRepository.findByProductId(4L);
    assertThat(stock).isPresent();
    assertThat(stock.get().getAvailableQuantity()).isEqualTo(50);
  }
}
