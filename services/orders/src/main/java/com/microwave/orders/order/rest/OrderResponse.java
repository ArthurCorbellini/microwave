package com.microwave.orders.order.rest;

import com.microwave.orders.order.Order;
import com.microwave.orders.order.OrderStatus;

import java.math.BigDecimal;

public record OrderResponse(Long id, Long productId, int quantity, BigDecimal totalAmount, OrderStatus status) {

  public static OrderResponse from(Order order) {
    return new OrderResponse(
        order.getId(), order.getProductId(), order.getQuantity(), order.getTotalAmount(), order.getStatus());
  }
}
