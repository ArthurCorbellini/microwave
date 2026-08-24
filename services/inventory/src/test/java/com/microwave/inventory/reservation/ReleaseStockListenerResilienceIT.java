package com.microwave.inventory.reservation;

import com.microwave.inventory.config.RabbitMQConfig;
import com.microwave.inventory.reservation.messaging.ReleaseStockCommand;
import com.microwave.inventory.stock.Stock;
import com.microwave.inventory.stock.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class ReleaseStockListenerResilienceIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private ReservationRepository reservationRepository;

  @Autowired
  private StockRepository stockRepository;

  @Test
  void isIdempotentForADuplicateCommand() {
    stockRepository.save(new Stock(11L, 45));
    reservationRepository.save(new Reservation(51L, 11L, 5, ReservationStatus.RESERVED));
    ReleaseStockCommand command = new ReleaseStockCommand(51L);

    rabbitTemplate.convertAndSend(RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY, command);
    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Reservation reservation = reservationRepository.findByOrderId(51L).orElseThrow();
      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    });

    rabbitTemplate.convertAndSend(RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY, command);

    // Give the second delivery time to process, then assert Stock was only
    // restored once (45 + 5 = 50, not 45 + 5 + 5 = 55).
    await().pollDelay(2, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Optional<Stock> stock = stockRepository.findByProductId(11L);
      assertThat(stock).isPresent();
      assertThat(stock.get().getAvailableQuantity()).isEqualTo(50);
    });
  }

  @Test
  void deadLettersAMessageThatAlwaysFailsToProcess() {
    // No Reservation exists for this orderId, so ReservationService.release
    // always throws ReservationNotFoundException — guaranteed to exhaust all
    // 3 retries and land on the dead-letter queue.
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY,
        new ReleaseStockCommand(999L));

    ReleaseStockCommand deadLettered =
        (ReleaseStockCommand) rabbitTemplate.receiveAndConvert(RabbitMQConfig.RELEASE_STOCK_DLQ, 15000);
    assertThat(deadLettered).isNotNull();
    assertThat(deadLettered.orderId()).isEqualTo(999L);
  }
}
