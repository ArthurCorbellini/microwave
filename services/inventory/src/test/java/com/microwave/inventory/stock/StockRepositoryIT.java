package com.microwave.inventory.stock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class StockRepositoryIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired
  private StockRepository stockRepository;

  @Test
  void savesAndFindsStockByProductId() {
    stockRepository.save(new Stock(1L, 50));

    Optional<Stock> found = stockRepository.findByProductId(1L);

    assertThat(found).isPresent();
    assertThat(found.get().getAvailableQuantity()).isEqualTo(50);
  }

  @Test
  void returnsEmptyWhenProductHasNoStockRow() {
    Optional<Stock> found = stockRepository.findByProductId(999L);

    assertThat(found).isEmpty();
  }
}
