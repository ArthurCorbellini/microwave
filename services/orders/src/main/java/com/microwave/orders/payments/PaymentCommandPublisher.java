package com.microwave.orders.payments;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.payments.messaging.ChargePaymentCommand;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PaymentCommandPublisher {

  private final RabbitTemplate rabbitTemplate;

  public PaymentCommandPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  public void sendChargePayment(Long orderId, BigDecimal amount) {
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY,
        new ChargePaymentCommand(orderId, amount));
  }
}
