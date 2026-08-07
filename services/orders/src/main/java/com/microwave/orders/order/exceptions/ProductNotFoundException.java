package com.microwave.orders.order.exceptions;

public class ProductNotFoundException extends RuntimeException {

  public ProductNotFoundException(Long productId) {
    super("Product not found: " + productId);
  }
}
