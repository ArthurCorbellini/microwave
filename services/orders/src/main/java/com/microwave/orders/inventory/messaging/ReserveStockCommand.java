package com.microwave.orders.inventory.messaging;

public record ReserveStockCommand(Long orderId, Long productId, int quantity) {
}
