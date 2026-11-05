package com.microinv.inventory.product;

import com.microinv.inventory.stock.StockLimits;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Cuerpo de {@code POST /api/products} (contrato 6.2). */
public record CreateProductRequest(
        @NotBlank(message = "is required")
        @Size(max = 40, message = "must be at most 40 characters")
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "may only contain letters, digits, '.', '_' and '-'")
        String sku,

        @NotBlank(message = "is required")
        @Size(max = 120, message = "must be at most 120 characters")
        String name,

        @Size(max = 1000, message = "must be at most 1000 characters")
        String description,

        @NotNull(message = "is required")
        @DecimalMin(value = "0", message = "must be greater than or equal to 0")
        @DecimalMax(value = "999999.99", message = "must be at most 999999.99")
        @Digits(integer = 6, fraction = 2, message = "must have at most 2 decimal places")
        BigDecimal price,

        @NotNull(message = "is required")
        @Min(value = 0, message = "must be greater than or equal to 0")
        @Max(value = StockLimits.MAX_STOCK, message = "must be at most 1000000000")
        Integer stock,

        @Min(value = 0, message = "must be greater than or equal to 0")
        Integer lowStockThreshold) {

    public static final int DEFAULT_LOW_STOCK_THRESHOLD = 5;
}
