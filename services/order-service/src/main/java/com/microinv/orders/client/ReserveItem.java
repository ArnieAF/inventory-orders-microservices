package com.microinv.orders.client;

import java.util.UUID;

/** Item de la peticion de reserva. */
public record ReserveItem(UUID productId, int quantity) {
}
