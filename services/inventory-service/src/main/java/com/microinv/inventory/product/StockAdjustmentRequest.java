package com.microinv.inventory.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code POST /api/products/{id}/stock-adjustments}: {@code delta} entero distinto de 0, motivo 1-200. */
public record StockAdjustmentRequest(
        @NotNull(message = "is required")
        @NotZero(message = "must not be zero")
        Integer delta,

        @NotBlank(message = "is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String reason) {
}
