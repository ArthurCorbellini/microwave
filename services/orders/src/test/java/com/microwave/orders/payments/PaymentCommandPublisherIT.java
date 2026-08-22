package com.microwave.orders.payments;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.payments.messaging.ChargePaymentCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PaymentCommandPublisherIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  private static final String TEST_COMMAND_QUEUE = "test.payments.charge-payment.queue";

  @Autowired
  private PaymentCommandPublisher paymentCommandPublisher;

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @BeforeEach
  void bindTestCommandQueue() {
    Queue queue = new Queue(TEST_COMMAND_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(queue);
    Binding binding = BindingBuilder.bind(queue)
        .to(new DirectExchange(RabbitMQConfig.PAYMENTS_EXCHANGE))
        .with(RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY);
    rabbitAdmin.declareBinding(binding);
  }

  @Test
  void publishesChargePaymentCommand() {
    paymentCommandPublisher.sendChargePayment(42L, new BigDecimal("150.00"));

    ChargePaymentCommand received =
        (ChargePaymentCommand) rabbitTemplate.receiveAndConvert(TEST_COMMAND_QUEUE, 10000);

    assertThat(received).isNotNull();
    assertThat(received.orderId()).isEqualTo(42L);
    assertThat(received.amount()).isEqualByComparingTo("150.00");
  }
}
