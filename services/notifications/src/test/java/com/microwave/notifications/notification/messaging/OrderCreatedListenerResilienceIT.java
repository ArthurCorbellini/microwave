package com.microwave.notifications.notification.messaging;

import com.microwave.notifications.config.KafkaConfig;
import com.microwave.notifications.notification.NotificationLogRepository;
import com.microwave.notifications.notification.enums.NotificationType;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class OrderCreatedListenerResilienceIT {

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
  void isIdempotentForADuplicateEvent() {
    OrderCreatedEvent event = new OrderCreatedEvent(55L, 1L, 2, new BigDecimal("200.00"), Instant.now());

    kafkaTemplate.send(KafkaConfig.ORDER_CREATED_TOPIC, event.orderId().toString(), event);
    await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
        assertThat(notificationLogRepository.findByOrderIdAndType(55L, NotificationType.ORDER_CREATED)).isPresent());

    kafkaTemplate.send(KafkaConfig.ORDER_CREATED_TOPIC, event.orderId().toString(), event);
    // Give the redelivered event time to be (not) processed before asserting
    // there's still only one row — a plain isPresent() check can't tell
    // "processed once" from "processed twice", so count explicitly.
    await().pollDelay(3, TimeUnit.SECONDS).atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
      long count = notificationLogRepository.findAll().stream()
          .filter(log -> log.getOrderId().equals(55L))
          .count();
      assertThat(count).isEqualTo(1);
    });
  }

  @Test
  void deadLettersAnEventThatAlwaysFailsToProcess() {
    // orderId=null violates NotificationLog's not-null column constraint on
    // save — OrderCreatedListener catches nothing, so this exhausts all 3
    // retries and lands on the "orders.order-created.DLT" topic.
    OrderCreatedEvent event = new OrderCreatedEvent(null, 1L, 2, new BigDecimal("200.00"), Instant.now());
    kafkaTemplate.send(KafkaConfig.ORDER_CREATED_TOPIC, "dead-letter-test", event);

    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-consumer");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
    props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.microwave.notifications.*");
    props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
        "com.microwave.notifications.notification.messaging.OrderCreatedEvent");

    try (KafkaConsumer<String, OrderCreatedEvent> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(KafkaConfig.ORDER_CREATED_TOPIC + "-dlt"));

      ConsumerRecords<String, OrderCreatedEvent> records = ConsumerRecords.empty();
      long deadline = System.currentTimeMillis() + 15000;
      while (records.isEmpty() && System.currentTimeMillis() < deadline) {
        records = consumer.poll(Duration.ofSeconds(1));
      }

      assertThat(records.count()).isEqualTo(1);
      ConsumerRecord<String, OrderCreatedEvent> record = records.iterator().next();
      assertThat(record.value().productId()).isEqualTo(1L);
    }
  }
}
