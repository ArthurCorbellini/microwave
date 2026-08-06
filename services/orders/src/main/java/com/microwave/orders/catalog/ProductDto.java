package com.microwave.orders.catalog;

import java.math.BigDecimal;

public record ProductDto(Long id, String name, String description, BigDecimal price) {
}
