package com.microwave.payments.payment.util;

import com.microwave.payments.payment.enums.PaymentStatus;

import java.math.BigDecimal;

public final class PaymentSimulator {

  private static final BigDecimal APPROVAL_LIMIT = new BigDecimal("10000");

  private PaymentSimulator() {
  }

  public static PaymentStatus decide(BigDecimal amount) {
    return amount.compareTo(APPROVAL_LIMIT) <= 0 ? PaymentStatus.APPROVED : PaymentStatus.REJECTED;
  }
}
