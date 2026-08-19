package com.microwave.inventory.reservation.messaging;

import com.microwave.inventory.config.RabbitMQConfig;
import com.microwave.inventory.reservation.ReservationService;
import com.microwave.inventory.reservation.exceptions.InsufficientStockException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReserveStockListener {

  private final ReservationService reservationService;
  private final RabbitTemplate rabbitTemplate;

  public ReserveStockListener(ReservationService reservationService, RabbitTemplate rabbitTemplate) {
    this.reservationService = reservationService;
    this.rabbitTemplate = rabbitTemplate;
  }

  @RabbitListener(queues = RabbitMQConfig.RESERVE_STOCK_QUEUE, containerFactory = "rabbitListenerContainerFactory")
  public void handle(ReserveStockCommand command) {
    InventoryReservedReply reply;
    try {
      reservationService.reserve(command.orderId(), command.productId(), command.quantity());
      reply = InventoryReservedReply.reserved(command.orderId());
    } catch (InsufficientStockException ex) {
      reply = InventoryReservedReply.notReserved(command.orderId(), "OUT_OF_STOCK");
    }

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.INVENTORY_RESERVED_ROUTING_KEY, reply);
  }
}
