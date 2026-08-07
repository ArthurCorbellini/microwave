package com.microwave.orders.payments.dto;

import com.microwave.orders.payments.enums.PaymentStatus;

import java.math.BigDecimal;

public record PaymentResponse(Long id, Long orderId, BigDecimal amount, PaymentStatus status) {
}
