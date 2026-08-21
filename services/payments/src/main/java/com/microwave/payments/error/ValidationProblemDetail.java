package com.microwave.payments.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.List;
import java.util.Objects;

// A typed errors field (vs. ProblemDetail's generic setProperty extension map)
// so springdoc's reflection-based schema generation can see it too.
public class ValidationProblemDetail extends ProblemDetail {

  // transient: this class is only ever serialized to JSON (Jackson uses the
  // getter below, ignoring the field's transient marker), never via java.io -
  // FieldErrorDetail doesn't implement Serializable, so without this marker
  // an actual java.io serialization attempt would fail.
  private final transient List<FieldErrorDetail> errors;

  public ValidationProblemDetail(HttpStatus status, String detail, List<FieldErrorDetail> errors) {
    super(status.value());
    setDetail(detail);
    this.errors = errors;
  }

  public List<FieldErrorDetail> getErrors() {
    return errors;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ValidationProblemDetail that)) {
      return false;
    }
    return super.equals(other) && Objects.equals(errors, that.errors);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), errors);
  }
}
