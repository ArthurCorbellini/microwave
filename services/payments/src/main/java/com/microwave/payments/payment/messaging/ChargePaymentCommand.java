package com.microwave.payments.payment.messaging;

import java.math.BigDecimal;

public record ChargePaymentCommand(Long orderId, BigDecimal amount) {
}
