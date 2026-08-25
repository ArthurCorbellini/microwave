package com.microwave.inventory.reservation.dto;

public record ReserveStockCommand(Long orderId, Long productId, int quantity) {
}
