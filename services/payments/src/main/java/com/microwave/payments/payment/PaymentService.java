package com.microwave.payments.payment;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PaymentService {

  private final PaymentRepository paymentRepository;

  public PaymentService(PaymentRepository paymentRepository) {
    this.paymentRepository = paymentRepository;
  }

  public Payment charge(Long orderId, BigDecimal amount) {
    PaymentStatus status = PaymentSimulator.decide(amount);
    return paymentRepository.save(new Payment(orderId, amount, status));
  }

  public Payment findByOrderId(Long orderId) {
    return paymentRepository.findByOrderId(orderId)
        .orElseThrow(() -> new PaymentNotFoundException(orderId));
  }
}
