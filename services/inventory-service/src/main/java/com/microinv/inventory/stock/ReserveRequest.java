package com.microinv.inventory.stock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Cuerpo de {@code POST /internal/stock/reserve} (contrato 6.2). 1 a 50 lineas, como los pedidos. */
public record ReserveRequest(
        @NotNull(message = "is required") UUID orderRef,
        @NotEmpty(message = "must contain at least one item")
        @Size(max = 50, message = "must contain at most 50 items")
        List<@NotNull(message = "must not be null") @Valid Item> items) {

    public record Item(
            @NotNull(message = "is required") UUID productId,
            @NotNull(message = "is required") @Min(value = 1, message = "must be at least 1") Integer quantity) {
    }
}
