package com.microwave.orders.order;

import java.math.BigDecimal;

public record OrderResponse(Long id, Long productId, int quantity, BigDecimal totalAmount, OrderStatus status) {

    static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(), order.getProductId(), order.getQuantity(), order.getTotalAmount(), order.getStatus());
    }
}
