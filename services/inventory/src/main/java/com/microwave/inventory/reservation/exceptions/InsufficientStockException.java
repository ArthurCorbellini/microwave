package com.microwave.inventory.reservation.exceptions;

public class InsufficientStockException extends RuntimeException {

  public InsufficientStockException(Long productId) {
    super("Insufficient stock for product: " + productId);
  }
}
