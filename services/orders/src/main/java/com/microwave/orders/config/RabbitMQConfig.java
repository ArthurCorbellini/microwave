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
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
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

  @Bean
  DirectExchange ordersExchange() {
    return new DirectExchange(ORDERS_EXCHANGE);
  }

  @Bean
  DirectExchange inventoryExchange() {
    // Declared defensively so publishing ReserveStock never races against
    // inventory's own declaration of this exchange on startup.
    return new DirectExchange(INVENTORY_EXCHANGE);
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
  MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter("com.microwave.orders.inventory.messaging");
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
        .recoverer(new RepublishMessageRecoverer(rabbitTemplate, ORDERS_DLX, INVENTORY_RESERVED_ROUTING_KEY))
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
