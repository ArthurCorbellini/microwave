package com.microwave.orders.payments.rest;

import java.math.BigDecimal;

public record PaymentRequest(Long orderId, BigDecimal amount) {
}
