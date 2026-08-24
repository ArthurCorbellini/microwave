package com.microwave.payments.payment;

import com.microwave.payments.config.RabbitMQConfig;
import com.microwave.payments.payment.messaging.ChargePaymentCommand;
import com.microwave.payments.payment.messaging.PaymentProcessedReply;
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
class ChargePaymentListenerIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  private static final String TEST_REPLY_QUEUE = "test.orders.payment-reply.queue";

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @Autowired
  private PaymentRepository paymentRepository;

  @BeforeEach
  void setupTestQueue() {
    Queue testQueue = new Queue(TEST_REPLY_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(testQueue);

    Binding testBinding = BindingBuilder.bind(testQueue)
        .to(new DirectExchange(RabbitMQConfig.ORDERS_EXCHANGE))
        .with(RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY);
    rabbitAdmin.declareBinding(testBinding);
  }

  @Test
  void chargesAndRepliesApprovedWithinLimit() {
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY,
        new ChargePaymentCommand(42L, new BigDecimal("100.00")));

    PaymentProcessedReply reply =
        (PaymentProcessedReply) rabbitTemplate.receiveAndConvert(TEST_REPLY_QUEUE, 10000);
    assertThat(reply).isNotNull();
    assertThat(reply.orderId()).isEqualTo(42L);
    assertThat(reply.approved()).isTrue();

    Payment payment = paymentRepository.findByOrderId(42L).orElseThrow();
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
  }

  @Test
  void chargesAndRepliesDeclinedAboveLimit() {
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY,
        new ChargePaymentCommand(43L, new BigDecimal("15000.00")));

    PaymentProcessedReply reply =
        (PaymentProcessedReply) rabbitTemplate.receiveAndConvert(TEST_REPLY_QUEUE, 10000);
    assertThat(reply).isNotNull();
    assertThat(reply.orderId()).isEqualTo(43L);
    assertThat(reply.approved()).isFalse();
    assertThat(reply.reason()).isEqualTo("AMOUNT_EXCEEDS_LIMIT");
  }
}
