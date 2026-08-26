package com.microwave.inventory.reservation.dto;

public record InventoryReservedReply(Long orderId, boolean reserved, String reason) {

  public static InventoryReservedReply reserved(Long orderId) {
    return new InventoryReservedReply(orderId, true, null);
  }

  public static InventoryReservedReply notReserved(Long orderId, String reason) {
    return new InventoryReservedReply(orderId, false, reason);
  }
}
