package com.microwave.orders.payments.dto;

import java.math.BigDecimal;

public record PaymentRequest(Long orderId, BigDecimal amount) {
}
