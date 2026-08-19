package com.microwave.orders.inventory.messaging;

public record InventoryReservedReply(Long orderId, boolean reserved, String reason) {
}
