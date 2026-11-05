package com.microinv.orders.service;

import java.util.UUID;

/** Se publica dentro de la transaccion de creacion; se entrega a los oyentes solo DESPUES del commit. */
public record OrderCreatedEvent(UUID orderId, UUID actorUserId) {
}
