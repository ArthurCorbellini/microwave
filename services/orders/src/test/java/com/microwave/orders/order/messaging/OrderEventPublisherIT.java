package com.microwave.orders.order.messaging;

import com.microwave.orders.config.KafkaConfig;
import com.microwave.orders.order.Order;
import com.microwave.orders.order.enums.OrderStatus;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class OrderEventPublisherIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

  @Autowired
  private OrderEventPublisher orderEventPublisher;

  @Test
  void publishesOrderCreatedEvent() {
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED);
    ReflectionTestUtils.setField(order, "id", 42L);

    orderEventPublisher.publishOrderCreated(order);

    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "order-created-test-consumer");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
    props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.microwave.orders.*");
    props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "com.microwave.orders.order.messaging.OrderCreatedEvent");

    try (KafkaConsumer<String, OrderCreatedEvent> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(KafkaConfig.ORDER_CREATED_TOPIC));

      ConsumerRecords<String, OrderCreatedEvent> records = ConsumerRecords.empty();
      long deadline = System.currentTimeMillis() + 15000;
      while (records.isEmpty() && System.currentTimeMillis() < deadline) {
        records = consumer.poll(Duration.ofSeconds(1));
      }

      assertThat(records.count()).isEqualTo(1);
      OrderCreatedEvent event = records.iterator().next().value();
      assertThat(event.orderId()).isEqualTo(42L);
      assertThat(event.productId()).isEqualTo(1L);
      assertThat(event.totalAmount()).isEqualByComparingTo("200.00");
    }
  }
}
