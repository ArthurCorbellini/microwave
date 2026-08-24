package com.microwave.orders.payments.messaging;

import java.math.BigDecimal;

public record ChargePaymentCommand(Long orderId, BigDecimal amount) {
}
