package com.microinv.orders.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Linea de pedido ya resuelta con la instantanea que devolvio inventario (sku, nombre, precio unitario).
 * El {@code unitPrice} jamas proviene del cliente.
 */
public record OrderLine(UUID productId, String sku, String productName, BigDecimal unitPrice, int quantity) {
}
