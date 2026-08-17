package com.microwave.inventory.reservation.exceptions;

public class ReservationNotFoundException extends RuntimeException {

  public ReservationNotFoundException(Long orderId) {
    super("Reservation not found for order: " + orderId);
  }
}
