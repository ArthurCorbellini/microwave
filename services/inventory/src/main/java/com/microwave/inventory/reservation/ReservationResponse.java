package com.microwave.inventory.reservation;

import java.time.Instant;

public record ReservationResponse(
    Long orderId, Long productId, int quantity, ReservationStatus status, Instant createdAt) {

  public static ReservationResponse from(Reservation reservation) {
    return new ReservationResponse(
        reservation.getOrderId(), reservation.getProductId(), reservation.getQuantity(),
        reservation.getStatus(), reservation.getCreatedAt());
  }
}
