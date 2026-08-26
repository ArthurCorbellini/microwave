package com.microwave.orders.inventory.dto;

public record InventoryReservedReply(Long orderId, boolean reserved, String reason) {
}
