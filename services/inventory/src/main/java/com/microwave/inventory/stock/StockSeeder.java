package com.microwave.inventory.stock;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// Seeds a couple of demo products' stock for local docker-compose exploration
// via Postman — only active under the "demo" profile (set by docker-compose.yml,
// see Task 19), never during `mvn test`/`mvn verify` or a plain local run.
// Product ids 1/2 are illustrative; create matching products in `catalog` first
// (POST /products) for the demo to make sense end-to-end.
@Component
@Profile("demo")
public class StockSeeder implements CommandLineRunner {

  private final StockRepository stockRepository;

  public StockSeeder(StockRepository stockRepository) {
    this.stockRepository = stockRepository;
  }

  @Override
  public void run(String... args) {
    if (stockRepository.count() > 0) {
      return;
    }
    stockRepository.save(new Stock(1L, 50));
    stockRepository.save(new Stock(2L, 20));
  }
}
