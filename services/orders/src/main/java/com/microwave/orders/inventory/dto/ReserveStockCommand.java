package com.microwave.orders.inventory.dto;

public record ReserveStockCommand(Long orderId, Long productId, int quantity) {
}
