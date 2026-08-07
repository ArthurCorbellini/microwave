package com.microwave.catalog.product.exceptions;

public class ProductNotFoundException extends RuntimeException {

  public ProductNotFoundException(Long id) {
    super("Product not found: " + id);
  }
}
