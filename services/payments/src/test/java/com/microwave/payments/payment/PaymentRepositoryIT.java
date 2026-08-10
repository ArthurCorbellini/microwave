package com.microwave.payments.payment;

import com.microwave.payments.payment.enums.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PaymentRepositoryIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired
  private PaymentRepository paymentRepository;

  @Test
  void savesAndFindsPaymentByOrderId() {
    paymentRepository.save(new Payment(42L, new BigDecimal("100.00"), PaymentStatus.APPROVED));

    Optional<Payment> found = paymentRepository.findByOrderId(42L);

    assertThat(found).isPresent();
    assertThat(found.get().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    assertThat(found.get().getAmount()).isEqualByComparingTo("100.00");
  }
}
