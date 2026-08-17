package com.microwave.inventory.config;

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

  public static final String INVENTORY_EXCHANGE = "inventory.exchange";
  public static final String RESERVE_STOCK_QUEUE = "inventory.reserve-stock.queue";
  public static final String RESERVE_STOCK_ROUTING_KEY = "reserve-stock";
  public static final String INVENTORY_DLX = "inventory.dlx";
  public static final String RESERVE_STOCK_DLQ = "inventory.reserve-stock.dlq";

  public static final String ORDERS_EXCHANGE = "orders.exchange";
  public static final String INVENTORY_RESERVED_ROUTING_KEY = "inventory-reserved";

  @Bean
  DirectExchange inventoryExchange() {
    return new DirectExchange(INVENTORY_EXCHANGE);
  }

  @Bean
  DirectExchange ordersExchange() {
    // Declared defensively so publishing a reply never races against orders'
    // own declaration of this exchange on startup — declaration is idempotent.
    return new DirectExchange(ORDERS_EXCHANGE);
  }

  @Bean
  DirectExchange inventoryDeadLetterExchange() {
    return new DirectExchange(INVENTORY_DLX);
  }

  @Bean
  Queue reserveStockQueue() {
    return QueueBuilder.durable(RESERVE_STOCK_QUEUE)
        .withArgument("x-dead-letter-exchange", INVENTORY_DLX)
        .withArgument("x-dead-letter-routing-key", RESERVE_STOCK_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue reserveStockDeadLetterQueue() {
    return QueueBuilder.durable(RESERVE_STOCK_DLQ).build();
  }

  @Bean
  Binding reserveStockBinding() {
    return BindingBuilder.bind(reserveStockQueue()).to(inventoryExchange()).with(RESERVE_STOCK_ROUTING_KEY);
  }

  @Bean
  Binding reserveStockDeadLetterBinding() {
    return BindingBuilder.bind(reserveStockDeadLetterQueue()).to(inventoryDeadLetterExchange())
        .with(RESERVE_STOCK_ROUTING_KEY);
  }

  @Bean
  MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter("com.microwave.inventory.reservation.messaging");
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
        .recoverer(new RepublishMessageRecoverer(rabbitTemplate, INVENTORY_DLX, RESERVE_STOCK_ROUTING_KEY))
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
