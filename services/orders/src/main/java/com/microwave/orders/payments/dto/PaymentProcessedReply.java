package com.microwave.orders.payments.dto;

public record PaymentProcessedReply(Long orderId, boolean approved, String reason) {
}
