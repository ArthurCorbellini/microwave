package com.microwave.payments.payment;

import com.microwave.payments.payment.enums.PaymentStatus;
import com.microwave.payments.payment.util.PaymentSimulator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSimulatorTest {

  @Test
  void approvesAmountAtOrBelowLimit() {
    assertThat(PaymentSimulator.decide(new BigDecimal("10000"))).isEqualTo(PaymentStatus.APPROVED);
    assertThat(PaymentSimulator.decide(new BigDecimal("1")))
        .isEqualTo(PaymentStatus.APPROVED);
  }

  @Test
  void rejectsAmountAboveLimit() {
    assertThat(PaymentSimulator.decide(new BigDecimal("10000.01"))).isEqualTo(PaymentStatus.REJECTED);
  }
}
