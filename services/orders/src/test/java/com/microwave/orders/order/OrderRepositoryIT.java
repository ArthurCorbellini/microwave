package com.microwave.orders.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class OrderRepositoryIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired
  private OrderRepository orderRepository;

  @Test
  void savesAndFindsOrder() {
    Order saved = orderRepository.save(
        new Order(1L, 2, new BigDecimal("700.00"), OrderStatus.CREATED));

    Optional<Order> found = orderRepository.findById(saved.getId());

    assertThat(found).isPresent();
    assertThat(found.get().getStatus()).isEqualTo(OrderStatus.CREATED);
    assertThat(found.get().getTotalAmount()).isEqualByComparingTo("700.00");
  }

  @Test
  void updatesOrderStatus() {
    Order saved = orderRepository.save(
        new Order(1L, 2, new BigDecimal("700.00"), OrderStatus.CREATED));

    saved.updateStatus(OrderStatus.CONFIRMED);
    orderRepository.save(saved);

    Optional<Order> found = orderRepository.findById(saved.getId());
    assertThat(found).isPresent();
    assertThat(found.get().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
  }

  @Test
  void throwsOptimisticLockingFailureOnStaleUpdate() {
    Order saved = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));

    Order copy1 = orderRepository.findById(saved.getId()).orElseThrow();
    Order copy2 = orderRepository.findById(saved.getId()).orElseThrow();

    copy1.updateStatus(OrderStatus.CONFIRMED);
    orderRepository.saveAndFlush(copy1);

    copy2.updateStatus(OrderStatus.REJECTED);
    assertThatThrownBy(() -> orderRepository.saveAndFlush(copy2))
        .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
  }
}
