package com.microwave.payments.payment.exceptions;

public class PaymentNotFoundException extends RuntimeException {

  public PaymentNotFoundException(Long orderId) {
    super("Payment not found for order: " + orderId);
  }
}
