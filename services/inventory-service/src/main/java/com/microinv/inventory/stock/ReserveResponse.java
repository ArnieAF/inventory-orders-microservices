package com.microinv.inventory.stock;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Respuesta de la reserva: instantanea de precio/nombre/SKU de cada linea. Ordenada por productId ascendente. */
public record ReserveResponse(List<Item> items) {

    public record Item(UUID productId, String sku, String name, BigDecimal unitPrice, int quantity) {
    }
}
