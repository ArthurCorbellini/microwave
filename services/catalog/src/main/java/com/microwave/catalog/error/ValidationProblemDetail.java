package com.microwave.catalog.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.List;

// A typed errors field (vs. ProblemDetail's generic setProperty extension map)
// so springdoc's reflection-based schema generation can see it too.
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
