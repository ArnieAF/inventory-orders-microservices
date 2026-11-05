package com.microinv.orders.service;

import com.microinv.orders.domain.OrderStatus;
import java.util.UUID;

/** Se publica dentro de la transaccion del cambio de estado; se entrega solo DESPUES del commit. */
public record OrderStatusChangedEvent(
        UUID orderId,
        UUID createdBy,
        UUID actorUserId,
        OrderStatus from,
        OrderStatus to) {
}
