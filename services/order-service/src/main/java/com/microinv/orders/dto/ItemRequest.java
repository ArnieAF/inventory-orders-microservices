package com.microinv.orders.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Item de un pedido nuevo. Solo {@code productId} y {@code quantity}: el precio SIEMPRE lo fija inventario;
 * cualquier otro campo del body (precio, total, usuario...) se ignora.
 */
public record ItemRequest(
        @NotNull UUID productId,
        @NotNull @Min(1) @Max(10000) Integer quantity) {
}
