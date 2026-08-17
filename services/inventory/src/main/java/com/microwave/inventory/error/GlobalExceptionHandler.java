package com.microwave.inventory.error;

import com.microwave.inventory.reservation.exceptions.ReservationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ReservationNotFoundException.class)
  public ProblemDetail handleReservationNotFound(ReservationNotFoundException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ValidationProblemDetail handleValidationFailure(MethodArgumentNotValidException ex) {
    List<FieldErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
        .map(error -> new FieldErrorDetail(error.getField(), error.getDefaultMessage()))
        .toList();

    return new ValidationProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed", errors);
  }
}
