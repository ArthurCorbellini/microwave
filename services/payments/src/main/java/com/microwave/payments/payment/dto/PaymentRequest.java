package com.microwave.payments.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentRequest(
    @NotNull Long orderId,
    @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount) {
}
