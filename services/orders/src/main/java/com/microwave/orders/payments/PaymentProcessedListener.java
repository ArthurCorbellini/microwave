package com.microwave.orders.payments;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.order.OrderService;
import com.microwave.orders.payments.messaging.PaymentProcessedReply;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentProcessedListener {

  private final OrderService orderService;

  public PaymentProcessedListener(OrderService orderService) {
    this.orderService = orderService;
  }

  @RabbitListener(queues = RabbitMQConfig.PAYMENT_PROCESSED_QUEUE, containerFactory = "paymentReplyListenerContainerFactory")
  public void handle(PaymentProcessedReply reply) {
    orderService.handlePaymentProcessed(reply);
  }
}
