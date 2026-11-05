package com.microinv.inventory.stock;

import java.util.UUID;

/**
 * Evento de dominio: un producto cruzo su umbral de stock bajo. Se publica dentro de la transaccion y solo
 * se atiende tras el commit (si la transaccion se revierte, nunca se envia).
 *
 * @param actorUserId usuario que provoco el cambio (ajuste manual) o {@code null} (reservas).
 */
public record LowStockEvent(UUID productId, String sku, String name, int stock, int threshold, UUID actorUserId) {
}
