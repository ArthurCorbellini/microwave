package com.microwave.orders.order.exceptions;

public class OrderNotFoundException extends RuntimeException {

  public OrderNotFoundException(Long id) {
    super("Order not found: " + id);
  }
}
