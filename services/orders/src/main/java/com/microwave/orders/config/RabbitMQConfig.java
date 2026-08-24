package com.microwave.orders.config;

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

  public static final String ORDERS_EXCHANGE = "orders.exchange";
  public static final String INVENTORY_RESERVED_QUEUE = "orders.inventory-reply.queue";
  public static final String INVENTORY_RESERVED_ROUTING_KEY = "inventory-reserved";
  public static final String ORDERS_DLX = "orders.dlx";
  public static final String INVENTORY_RESERVED_DLQ = "orders.inventory-reply.dlq";

  public static final String INVENTORY_EXCHANGE = "inventory.exchange";
  public static final String RESERVE_STOCK_ROUTING_KEY = "reserve-stock";
  public static final String RELEASE_STOCK_ROUTING_KEY = "release-stock";

  public static final String PAYMENTS_EXCHANGE = "payments.exchange";
  public static final String CHARGE_PAYMENT_ROUTING_KEY = "charge-payment";
  public static final String PAYMENT_PROCESSED_QUEUE = "orders.payment-reply.queue";
  public static final String PAYMENT_PROCESSED_ROUTING_KEY = "payment-processed";
  public static final String PAYMENT_PROCESSED_DLQ = "orders.payment-reply.dlq";

  @Bean
  DirectExchange ordersExchange() {
    return new DirectExchange(ORDERS_EXCHANGE);
  }

  @Bean
  DirectExchange inventoryExchange() {
    // Declared defensively so publishing ReserveStock/ReleaseStock never
    // races against inventory's own declaration of this exchange on startup.
    return new DirectExchange(INVENTORY_EXCHANGE);
  }

  @Bean
  DirectExchange paymentsExchange() {
    // Declared defensively so publishing ChargePayment never races against
    // payments' own declaration of this exchange on startup.
    return new DirectExchange(PAYMENTS_EXCHANGE);
  }

  @Bean
  DirectExchange ordersDeadLetterExchange() {
    return new DirectExchange(ORDERS_DLX);
  }

  @Bean
  Queue inventoryReservedQueue() {
    return QueueBuilder.durable(INVENTORY_RESERVED_QUEUE)
        .withArgument("x-dead-letter-exchange", ORDERS_DLX)
        .withArgument("x-dead-letter-routing-key", INVENTORY_RESERVED_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue inventoryReservedDeadLetterQueue() {
    return QueueBuilder.durable(INVENTORY_RESERVED_DLQ).build();
  }

  @Bean
  Binding inventoryReservedBinding() {
    return BindingBuilder.bind(inventoryReservedQueue()).to(ordersExchange()).with(INVENTORY_RESERVED_ROUTING_KEY);
  }

  @Bean
  Binding inventoryReservedDeadLetterBinding() {
    return BindingBuilder.bind(inventoryReservedDeadLetterQueue()).to(ordersDeadLetterExchange())
        .with(INVENTORY_RESERVED_ROUTING_KEY);
  }

  @Bean
  Queue paymentProcessedQueue() {
    return QueueBuilder.durable(PAYMENT_PROCESSED_QUEUE)
        .withArgument("x-dead-letter-exchange", ORDERS_DLX)
        .withArgument("x-dead-letter-routing-key", PAYMENT_PROCESSED_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue paymentProcessedDeadLetterQueue() {
    return QueueBuilder.durable(PAYMENT_PROCESSED_DLQ).build();
  }

  @Bean
  Binding paymentProcessedBinding() {
    return BindingBuilder.bind(paymentProcessedQueue()).to(ordersExchange()).with(PAYMENT_PROCESSED_ROUTING_KEY);
  }

  @Bean
  Binding paymentProcessedDeadLetterBinding() {
    return BindingBuilder.bind(paymentProcessedDeadLetterQueue()).to(ordersDeadLetterExchange())
        .with(PAYMENT_PROCESSED_ROUTING_KEY);
  }

  @Bean
  MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter(
        "com.microwave.orders.inventory.messaging", "com.microwave.orders.payments.messaging");
  }

  @Bean
  RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(jsonMessageConverter);
    return template;
  }

  @Bean
  StatelessRetryOperationsInterceptor inventoryReplyRetryInterceptor(RabbitTemplate rabbitTemplate) {
    return RetryInterceptorBuilder.stateless()
        .maxRetries(3)
        .backOffOptions(500, 2.0, 10_000)
        .recoverer(new RepublishMessageRecoverer(rabbitTemplate, ORDERS_DLX, INVENTORY_RESERVED_ROUTING_KEY))
        .build();
  }

  @Bean
  SimpleRabbitListenerContainerFactory inventoryReplyListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter,
      StatelessRetryOperationsInterceptor inventoryReplyRetryInterceptor) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(jsonMessageConverter);
    factory.setAdviceChain(inventoryReplyRetryInterceptor);
    return factory;
  }

  @Bean
  StatelessRetryOperationsInterceptor paymentReplyRetryInterceptor(RabbitTemplate rabbitTemplate) {
    return RetryInterceptorBuilder.stateless()
        .maxRetries(3)
        .backOffOptions(500, 2.0, 10_000)
        .recoverer(new RepublishMessageRecoverer(rabbitTemplate, ORDERS_DLX, PAYMENT_PROCESSED_ROUTING_KEY))
        .build();
  }

  @Bean
  SimpleRabbitListenerContainerFactory paymentReplyListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter,
      StatelessRetryOperationsInterceptor paymentReplyRetryInterceptor) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(jsonMessageConverter);
    factory.setAdviceChain(paymentReplyRetryInterceptor);
    return factory;
  }
}
