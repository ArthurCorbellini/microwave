package com.microwave.orders.payments.messaging;

public record PaymentProcessedReply(Long orderId, boolean approved, String reason) {
}
