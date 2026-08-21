package com.microwave.orders.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationProblemDetailTest {

  @Test
  void instancesWithSameFieldsAreEqual() {
    List<FieldErrorDetail> errors = List.of(new FieldErrorDetail("productId", "must not be null"));
    ValidationProblemDetail first = new ValidationProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed", errors);
    ValidationProblemDetail second = new ValidationProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed", errors);

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  void instancesWithDifferentErrorsAreNotEqual() {
    ValidationProblemDetail first = new ValidationProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed",
        List.of(new FieldErrorDetail("productId", "must not be null")));
    ValidationProblemDetail second = new ValidationProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed",
        List.of(new FieldErrorDetail("quantity", "must be positive")));

    assertThat(first).isNotEqualTo(second);
  }
}
