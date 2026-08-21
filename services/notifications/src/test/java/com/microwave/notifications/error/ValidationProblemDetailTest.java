package com.microwave.notifications.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationProblemDetailTest {

  @Test
  void instancesWithSameFieldsAreEqual() {
    List<FieldErrorDetail> errors = List.of(new FieldErrorDetail("orderId", "must not be null"));
    ValidationProblemDetail first = new ValidationProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed", errors);
    ValidationProblemDetail second = new ValidationProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed", errors);

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  void instancesWithDifferentErrorsAreNotEqual() {
    ValidationProblemDetail first = new ValidationProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed",
        List.of(new FieldErrorDetail("orderId", "must not be null")));
    ValidationProblemDetail second = new ValidationProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed",
        List.of(new FieldErrorDetail("type", "must not be blank")));

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void equalsIsReflexiveAndRejectsNullAndDifferentType() {
    ValidationProblemDetail detail = new ValidationProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed",
        List.of(new FieldErrorDetail("orderId", "must not be null")));

    assertThat(detail.equals(detail)).isTrue();
    assertThat(detail.equals(null)).isFalse();
    assertThat(detail.equals("not a ValidationProblemDetail")).isFalse();
  }
}
