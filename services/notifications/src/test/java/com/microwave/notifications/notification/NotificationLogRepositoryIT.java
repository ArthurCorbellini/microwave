package com.microwave.notifications.notification;

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
class NotificationLogRepositoryIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired
  private NotificationLogRepository notificationLogRepository;

  @Test
  void savesAndFindsByOrderId() {
    notificationLogRepository.save(new NotificationLog(1L, NotificationType.ORDER_CREATED, "Order #1 created"));

    Optional<NotificationLog> found = notificationLogRepository.findByOrderId(1L);

    assertThat(found).isPresent();
    assertThat(found.get().getMessage()).isEqualTo("Order #1 created");
  }

  @Test
  void findsByOrderIdAndType() {
    notificationLogRepository.save(new NotificationLog(2L, NotificationType.ORDER_CREATED, "Order #2 created"));

    Optional<NotificationLog> found =
        notificationLogRepository.findByOrderIdAndType(2L, NotificationType.ORDER_CREATED);

    assertThat(found).isPresent();
  }

  @Test
  void returnsEmptyWhenNoneExists() {
    Optional<NotificationLog> found = notificationLogRepository.findByOrderId(999L);

    assertThat(found).isEmpty();
  }
}
