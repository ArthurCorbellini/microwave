package com.microwave.notifications.notification;

import com.microwave.notifications.config.KafkaConfig;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedListener {

  private final NotificationService notificationService;

  public OrderCreatedListener(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @KafkaListener(topics = KafkaConfig.ORDER_CREATED_TOPIC)
  public void handle(OrderCreatedEvent event) {
    notificationService.recordOrderCreated(event.orderId(), "Order #" + event.orderId() + " created");
  }
}
