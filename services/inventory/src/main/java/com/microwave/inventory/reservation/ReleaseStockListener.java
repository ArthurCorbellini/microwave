package com.microwave.inventory.reservation;

import com.microwave.inventory.config.RabbitMQConfig;
import com.microwave.inventory.reservation.messaging.ReleaseStockCommand;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ReleaseStockListener {

  private final ReservationService reservationService;

  public ReleaseStockListener(ReservationService reservationService) {
    this.reservationService = reservationService;
  }

  @RabbitListener(queues = RabbitMQConfig.RELEASE_STOCK_QUEUE, containerFactory = "releaseStockListenerContainerFactory")
  public void handle(ReleaseStockCommand command) {
    reservationService.release(command.orderId());
  }
}
