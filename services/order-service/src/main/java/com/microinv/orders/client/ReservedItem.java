package com.microinv.orders.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Instantanea del producto devuelta por inventario. Los campos pueden ser {@code null} si inventario
 * respondio algo incompleto; {@code OrderLines} lo valida antes de usarlos.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservedItem(UUID productId, String sku, String name, BigDecimal unitPrice, Integer quantity) {
}
