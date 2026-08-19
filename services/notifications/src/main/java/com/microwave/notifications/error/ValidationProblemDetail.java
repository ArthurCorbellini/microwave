package com.microwave.notifications.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.List;

public class ValidationProblemDetail extends ProblemDetail {

  private final List<FieldErrorDetail> errors;

  public ValidationProblemDetail(HttpStatus status, String detail, List<FieldErrorDetail> errors) {
    super(status.value());
    setDetail(detail);
    this.errors = errors;
  }

  public List<FieldErrorDetail> getErrors() {
    return errors;
  }
}
