package com.microinv.orders.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body de {@code POST /api/orders} (contrato 6.3). */
public record CreateOrderRequest(
        @NotBlank
        @Size(max = 120)
        @Pattern(regexp = "[^\\p{Cc}]*", message = "must not contain control characters")
        String customerName,

        @Size(max = 500)
        // Se permiten saltos de linea y tabuladores; el resto de caracteres de control no (p. ej. NUL rompe PostgreSQL).
        @Pattern(regexp = "[\\P{Cc}\\n\\r\\t]*", message = "must not contain control characters")
        String notes,

        @NotNull
        @NotEmpty
        @Size(max = 50)
        List<@NotNull @Valid ItemRequest> items) {
}
