package com.microwave.orders.inventory;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.inventory.messaging.InventoryReservedReply;
import com.microwave.orders.order.OrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryReservedListener {

  private final OrderService orderService;

  public InventoryReservedListener(OrderService orderService) {
    this.orderService = orderService;
  }

  @RabbitListener(queues = RabbitMQConfig.INVENTORY_RESERVED_QUEUE, containerFactory = "inventoryReplyListenerContainerFactory")
  public void handle(InventoryReservedReply reply) {
    orderService.handleInventoryReserved(reply);
  }
}
