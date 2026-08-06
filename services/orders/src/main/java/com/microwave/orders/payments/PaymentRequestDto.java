package com.microwave.orders.payments;

import java.math.BigDecimal;

public record PaymentRequestDto(Long orderId, BigDecimal amount) {
}
