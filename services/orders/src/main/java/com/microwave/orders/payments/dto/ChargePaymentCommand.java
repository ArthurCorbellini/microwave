package com.microwave.orders.payments.dto;

import java.math.BigDecimal;

public record ChargePaymentCommand(Long orderId, BigDecimal amount) {
}
