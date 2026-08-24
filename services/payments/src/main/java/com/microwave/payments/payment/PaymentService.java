package com.microwave.payments.payment;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class PaymentService {

  private final PaymentRepository paymentRepository;

  public PaymentService(PaymentRepository paymentRepository) {
    this.paymentRepository = paymentRepository;
  }

  // Idempotent: a redelivered command for an orderId that's already been
  // charged returns the existing Payment instead of processing it again —
  // same pattern as inventory's ReservationService.reserve.
  public Payment charge(Long orderId, BigDecimal amount) {
    Optional<Payment> existing = paymentRepository.findByOrderId(orderId);
    if (existing.isPresent()) {
      return existing.get();
    }

    PaymentStatus status = PaymentSimulator.decide(amount);
    return paymentRepository.save(new Payment(orderId, amount, status));
  }

  public Payment findByOrderId(Long orderId) {
    return paymentRepository.findByOrderId(orderId)
        .orElseThrow(() -> new PaymentNotFoundException(orderId));
  }
}
