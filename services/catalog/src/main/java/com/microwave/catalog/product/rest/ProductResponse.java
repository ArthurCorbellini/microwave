package com.microwave.catalog.product.rest;

import com.microwave.catalog.product.Product;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, String description, BigDecimal price) {

  public static ProductResponse from(Product product) {
    return new ProductResponse(product.getId(), product.getName(), product.getDescription(), product.getPrice());
  }
}
