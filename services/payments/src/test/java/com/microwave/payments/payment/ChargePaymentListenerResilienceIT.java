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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ChargePaymentListenerResilienceIT {

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
  void bindTestReplyQueue() {
    // autoDelete=false: the idempotency test performs two receiveAndConvert
    // calls in a row, each of which opens and cancels a consumer. With
    // autoDelete=true, the queue is removed the instant the first consumer
    // disconnects, so the second receive would fail with NOT_FOUND. The
    // queue is still scoped to this test class's Testcontainers broker, so
    // there's no real leak risk.
    Queue queue = new Queue(TEST_REPLY_QUEUE, true, false, false);
    rabbitAdmin.declareQueue(queue);
    Binding binding = BindingBuilder.bind(queue)
        .to(new DirectExchange(RabbitMQConfig.ORDERS_EXCHANGE))
        .with(RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY);
    rabbitAdmin.declareBinding(binding);
  }

  @Test
  void isIdempotentForADuplicateCommand() {
    ChargePaymentCommand command = new ChargePaymentCommand(44L, new BigDecimal("100.00"));

    rabbitTemplate.convertAndSend(RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY, command);
    rabbitTemplate.receiveAndConvert(TEST_REPLY_QUEUE, 10000);

    rabbitTemplate.convertAndSend(RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY, command);
    PaymentProcessedReply secondReply =
        (PaymentProcessedReply) rabbitTemplate.receiveAndConvert(TEST_REPLY_QUEUE, 10000);

    assertThat(secondReply).isNotNull();
    assertThat(secondReply.approved()).isTrue();

    // Only one Payment row exists for this orderId — the second delivery hit
    // the idempotency check in PaymentService.charge() and never saved again.
    List<Payment> payments = paymentRepository.findAll().stream()
        .filter(p -> p.getOrderId().equals(44L))
        .toList();
    assertThat(payments).hasSize(1);
  }

  @Test
  void deadLettersAMessageThatAlwaysFailsToProcess() {
    // A missing order id breaks Payment's not-null column constraint when
    // saving. PaymentService.charge only special-cases an already-existing
    // Payment, so this kind of failure is guaranteed to exhaust all 3
    // retries and land on the dead-letter queue.
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY,
        new ChargePaymentCommand(null, new BigDecimal("100.00")));

    ChargePaymentCommand deadLettered =
        (ChargePaymentCommand) rabbitTemplate.receiveAndConvert(RabbitMQConfig.CHARGE_PAYMENT_DLQ, 15000);
    assertThat(deadLettered).isNotNull();
    assertThat(deadLettered.amount()).isEqualByComparingTo("100.00");
  }
}
