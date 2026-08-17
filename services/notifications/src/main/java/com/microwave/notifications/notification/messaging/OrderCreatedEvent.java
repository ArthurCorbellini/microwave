package com.microwave.notifications.notification.messaging;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderCreatedEvent(Long orderId, Long productId, int quantity, BigDecimal totalAmount, Instant createdAt) {
}
