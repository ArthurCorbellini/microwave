package com.microwave.payments.config;

import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenApiCustomizer hideGenericPropertiesField() {
    return openApi -> {
      for (String schemaName : List.of("ProblemDetail", "ValidationProblemDetail")) {
        Schema<?> schema = openApi.getComponents().getSchemas().get(schemaName);
        if (schema != null) {
          schema.getProperties().remove("properties");
        }
      }
    };
  }
}
