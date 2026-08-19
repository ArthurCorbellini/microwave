package com.microwave.payments.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

  @Mock
  private PaymentRepository paymentRepository;

  private PaymentService paymentService;

  private void initService() {
    paymentService = new PaymentService(paymentRepository);
  }

  @Test
  void approvesAndSavesPaymentWithinLimit() {
    initService();
    when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

    Payment result = paymentService.charge(1L, new BigDecimal("100.00"));

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.APPROVED);

    ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).save(captor.capture());
    assertThat(captor.getValue().getOrderId()).isEqualTo(1L);
    assertThat(captor.getValue().getAmount()).isEqualByComparingTo("100.00");
    assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.APPROVED);
  }

  @Test
  void rejectsAndSavesPaymentAboveLimit() {
    initService();
    when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

    Payment result = paymentService.charge(2L, new BigDecimal("15000.00"));

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.REJECTED);
  }

  @Test
  void findsPaymentByOrderId() {
    initService();
    Payment payment = new Payment(1L, new BigDecimal("100.00"), PaymentStatus.APPROVED);
    when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

    Payment result = paymentService.findByOrderId(1L);

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.APPROVED);
  }

  @Test
  void throwsWhenPaymentNotFound() {
    initService();
    when(paymentRepository.findByOrderId(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> paymentService.findByOrderId(99L))
        .isInstanceOf(PaymentNotFoundException.class)
        .hasMessage("Payment not found for order: 99");
  }
}
