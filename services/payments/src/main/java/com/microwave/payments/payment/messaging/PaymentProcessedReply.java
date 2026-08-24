package com.microwave.payments.payment.messaging;

public record PaymentProcessedReply(Long orderId, boolean approved, String reason) {

  public static PaymentProcessedReply approved(Long orderId) {
    return new PaymentProcessedReply(orderId, true, null);
  }

  public static PaymentProcessedReply declined(Long orderId, String reason) {
    return new PaymentProcessedReply(orderId, false, reason);
  }
}
