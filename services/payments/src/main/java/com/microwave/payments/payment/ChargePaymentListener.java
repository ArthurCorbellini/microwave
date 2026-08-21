package com.microwave.payments.payment;

import com.microwave.payments.config.RabbitMQConfig;
import com.microwave.payments.payment.messaging.ChargePaymentCommand;
import com.microwave.payments.payment.messaging.PaymentProcessedReply;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChargePaymentListener {

  private final PaymentService paymentService;
  private final RabbitTemplate rabbitTemplate;

  public ChargePaymentListener(PaymentService paymentService, RabbitTemplate rabbitTemplate) {
    this.paymentService = paymentService;
    this.rabbitTemplate = rabbitTemplate;
  }

  @RabbitListener(queues = RabbitMQConfig.CHARGE_PAYMENT_QUEUE, containerFactory = "rabbitListenerContainerFactory")
  public void handle(ChargePaymentCommand command) {
    Payment payment = paymentService.charge(command.orderId(), command.amount());
    PaymentProcessedReply reply = payment.getStatus() == PaymentStatus.APPROVED
        ? PaymentProcessedReply.approved(command.orderId())
        : PaymentProcessedReply.declined(command.orderId(), "AMOUNT_EXCEEDS_LIMIT");

    rabbitTemplate.convertAndSend(RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY, reply);
  }
}
