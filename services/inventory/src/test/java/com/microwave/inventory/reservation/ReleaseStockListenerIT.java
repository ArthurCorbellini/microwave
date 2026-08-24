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
class ReleaseStockListenerIT {

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
  void releasesReservationAndRestoresStock() {
    stockRepository.save(new Stock(10L, 45));
    reservationRepository.save(new Reservation(50L, 10L, 5, ReservationStatus.RESERVED));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY,
        new ReleaseStockCommand(50L));

    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Reservation reservation = reservationRepository.findByOrderId(50L).orElseThrow();
      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    });

    Optional<Stock> stock = stockRepository.findByProductId(10L);
    assertThat(stock).isPresent();
    assertThat(stock.get().getAvailableQuantity()).isEqualTo(50);
  }
}
