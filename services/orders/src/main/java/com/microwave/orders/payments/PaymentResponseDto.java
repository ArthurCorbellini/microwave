package com.microwave.orders.payments;

import java.math.BigDecimal;

public record PaymentResponseDto(Long id, Long orderId, BigDecimal amount, PaymentStatusDto status) {
}
