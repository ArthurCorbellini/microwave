package com.microwave.payments.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

  public static final String PAYMENTS_EXCHANGE = "payments.exchange";
  public static final String CHARGE_PAYMENT_QUEUE = "payments.charge-payment.queue";
  public static final String CHARGE_PAYMENT_ROUTING_KEY = "charge-payment";
  public static final String PAYMENTS_DLX = "payments.dlx";
  public static final String CHARGE_PAYMENT_DLQ = "payments.charge-payment.dlq";

  public static final String ORDERS_EXCHANGE = "orders.exchange";
  public static final String PAYMENT_PROCESSED_ROUTING_KEY = "payment-processed";

  @Bean
  DirectExchange paymentsExchange() {
    return new DirectExchange(PAYMENTS_EXCHANGE);
  }

  @Bean
  DirectExchange ordersExchange() {
    // Declared defensively so publishing a reply never races against orders'
    // own declaration of this exchange on startup — declaration is idempotent.
    return new DirectExchange(ORDERS_EXCHANGE);
  }

  @Bean
  DirectExchange paymentsDeadLetterExchange() {
    return new DirectExchange(PAYMENTS_DLX);
  }

  @Bean
  Queue chargePaymentQueue() {
    return QueueBuilder.durable(CHARGE_PAYMENT_QUEUE)
        .withArgument("x-dead-letter-exchange", PAYMENTS_DLX)
        .withArgument("x-dead-letter-routing-key", CHARGE_PAYMENT_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue chargePaymentDeadLetterQueue() {
    return QueueBuilder.durable(CHARGE_PAYMENT_DLQ).build();
  }

  @Bean
  Binding chargePaymentBinding() {
    return BindingBuilder.bind(chargePaymentQueue()).to(paymentsExchange()).with(CHARGE_PAYMENT_ROUTING_KEY);
  }

  @Bean
  Binding chargePaymentDeadLetterBinding() {
    return BindingBuilder.bind(chargePaymentDeadLetterQueue()).to(paymentsDeadLetterExchange())
        .with(CHARGE_PAYMENT_ROUTING_KEY);
  }

  @Bean
  MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter("com.microwave.payments.payment.messaging");
  }

  @Bean
  RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(jsonMessageConverter);
    return template;
  }

  @Bean
  StatelessRetryOperationsInterceptor retryInterceptor(RabbitTemplate rabbitTemplate) {
    return RetryInterceptorBuilder.stateless()
        .maxRetries(3)
        .backOffOptions(500, 2.0, 10_000)
        .recoverer(new RepublishMessageRecoverer(rabbitTemplate, PAYMENTS_DLX, CHARGE_PAYMENT_ROUTING_KEY))
        .build();
  }

  @Bean
  SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter,
      StatelessRetryOperationsInterceptor retryInterceptor) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(jsonMessageConverter);
    factory.setAdviceChain(retryInterceptor);
    return factory;
  }
}
