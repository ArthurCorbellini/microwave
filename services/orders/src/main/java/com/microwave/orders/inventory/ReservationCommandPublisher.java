package com.microwave.orders.inventory;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.inventory.messaging.ReleaseStockCommand;
import com.microwave.orders.inventory.messaging.ReserveStockCommand;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReservationCommandPublisher {

  private final RabbitTemplate rabbitTemplate;

  public ReservationCommandPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  public void sendReserveStock(Long orderId, Long productId, int quantity) {
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RESERVE_STOCK_ROUTING_KEY,
        new ReserveStockCommand(orderId, productId, quantity));
  }

  public void sendReleaseStock(Long orderId) {
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY,
        new ReleaseStockCommand(orderId));
  }
}
