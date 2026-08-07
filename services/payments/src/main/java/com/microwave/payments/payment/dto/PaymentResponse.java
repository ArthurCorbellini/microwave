package com.microwave.payments.payment.dto;

import com.microwave.payments.payment.Payment;
import com.microwave.payments.payment.enums.PaymentStatus;

import java.math.BigDecimal;

public record PaymentResponse(Long id, Long orderId, BigDecimal amount, PaymentStatus status) {

  public static PaymentResponse from(Payment payment) {
    return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getStatus());
  }
}
