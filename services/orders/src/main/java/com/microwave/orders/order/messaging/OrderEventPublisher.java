package com.microwave.orders.order.messaging;

import com.microwave.orders.config.KafkaConfig;
import com.microwave.orders.order.Order;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class OrderEventPublisher {

  private final KafkaTemplate<String, Object> kafkaTemplate;

  public OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void publishOrderCreated(Order order) {
    OrderCreatedEvent event = new OrderCreatedEvent(
        order.getId(), order.getProductId(), order.getQuantity(), order.getTotalAmount(), Instant.now());
    kafkaTemplate.send(KafkaConfig.ORDER_CREATED_TOPIC, order.getId().toString(), event);
  }
}
