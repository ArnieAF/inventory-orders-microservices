package com.microinv.inventory.stock;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Cuerpo de {@code POST /internal/stock/release}. */
public record ReleaseRequest(@NotNull(message = "is required") UUID orderRef) {
}
