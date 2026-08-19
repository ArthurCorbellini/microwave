package com.microwave.notifications.notification.messaging;

import com.microwave.notifications.config.KafkaConfig;
import com.microwave.notifications.notification.NotificationLog;
import com.microwave.notifications.notification.NotificationLogRepository;
import com.microwave.notifications.notification.enums.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class OrderCreatedListenerIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

  @Autowired
  private KafkaTemplate<String, Object> kafkaTemplate;

  @Autowired
  private NotificationLogRepository notificationLogRepository;

  @Test
  void recordsANotificationWhenOrderCreatedEventArrives() {
    OrderCreatedEvent event = new OrderCreatedEvent(42L, 1L, 2, new BigDecimal("200.00"), Instant.now());

    kafkaTemplate.send(KafkaConfig.ORDER_CREATED_TOPIC, event.orderId().toString(), event);

    await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      Optional<NotificationLog> log =
          notificationLogRepository.findByOrderIdAndType(42L, NotificationType.ORDER_CREATED);
      assertThat(log).isPresent();
      assertThat(log.get().getMessage()).contains("42");
    });
  }
}
