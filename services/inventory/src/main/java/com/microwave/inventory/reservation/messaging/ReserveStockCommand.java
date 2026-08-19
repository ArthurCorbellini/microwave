package com.microwave.inventory.reservation.messaging;

public record ReserveStockCommand(Long orderId, Long productId, int quantity) {
}
