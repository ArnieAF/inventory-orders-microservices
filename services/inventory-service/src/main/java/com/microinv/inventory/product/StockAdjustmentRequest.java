package com.microinv.inventory.product;

import com.microinv.inventory.stock.StockLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /api/products/{id}/stock-adjustments}: {@code delta} entero distinto de 0 con
 * {@code |delta| <= 1 000 000 000} (contrato 6.2) y motivo de 1 a 200 caracteres.
 */
public record StockAdjustmentRequest(
        @NotNull(message = "is required")
        @NotZero(message = "must not be zero")
        @Min(value = -StockLimits.MAX_ABS_DELTA, message = "must be at least -1000000000")
        @Max(value = StockLimits.MAX_ABS_DELTA, message = "must be at most 1000000000")
        Integer delta,

        @NotBlank(message = "is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String reason) {
}
