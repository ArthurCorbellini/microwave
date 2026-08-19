package com.microwave.orders.payments.rest;

import com.microwave.orders.payments.PaymentStatus;

import java.math.BigDecimal;

public record PaymentResponse(Long id, Long orderId, BigDecimal amount, PaymentStatus status) {
}
