package com.microwave.payments.payment.rest;

import com.microwave.payments.payment.Payment;
import com.microwave.payments.payment.PaymentStatus;

import java.math.BigDecimal;

public record PaymentResponse(Long id, Long orderId, BigDecimal amount, PaymentStatus status) {

  public static PaymentResponse from(Payment payment) {
    return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getStatus());
  }
}
