package com.microwave.catalog.product.rest;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductRequest(
    @NotBlank String name,
    String description,
    @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal price) {
}
