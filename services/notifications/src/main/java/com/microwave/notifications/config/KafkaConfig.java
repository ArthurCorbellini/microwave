package com.microwave.notifications.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

  public static final String ORDER_CREATED_TOPIC = "orders.order-created";

  @Bean
  DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
    // Spring Boot auto-wires this into the auto-configured
    // ConcurrentKafkaListenerContainerFactory — no need to redeclare that bean.
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
    ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
    backOff.setMaxAttempts(3);
    return new DefaultErrorHandler(recoverer, backOff);
  }
}
