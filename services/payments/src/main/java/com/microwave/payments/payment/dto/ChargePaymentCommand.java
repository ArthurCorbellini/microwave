package com.microwave.payments.payment.dto;

import java.math.BigDecimal;

public record ChargePaymentCommand(Long orderId, BigDecimal amount) {
}
