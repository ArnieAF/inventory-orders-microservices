package com.microinv.orders.service;

import com.microinv.orders.client.NotificationRequest;
import com.microinv.orders.client.NotificationType;
import java.util.UUID;

/**
 * Construye las notificaciones (contrato 6.4). Los textos van en espanol porque el frontend los pinta tal cual
 * (como texto, nunca HTML) y solo llevan el numero corto del pedido (prefijo del id) y el estado: nada del
 * cliente ni de sus notas.
 */
final class OrderNotifications {

    private OrderNotifications() {
    }

    static NotificationRequest newOrder(OrderCreatedEvent event) {
        return new NotificationRequest(
                NotificationType.NEW_ORDER,
                "Nuevo pedido",
                "Se creó el pedido " + number(event.orderId()),
                null, // broadcast
                event.actorUserId(),
                NotificationRequest.ENTITY_ORDER,
                event.orderId());
    }

    static NotificationRequest statusChanged(OrderStatusChangedEvent event) {
        return new NotificationRequest(
                NotificationType.ORDER_STATUS_CHANGED,
                "Estado del pedido actualizado",
                "El pedido " + number(event.orderId()) + " pasó de " + event.from() + " a " + event.to(),
                event.createdBy(),
                event.actorUserId(),
                NotificationRequest.ENTITY_ORDER,
                event.orderId());
    }

    /** Numero corto y legible del pedido: los 8 primeros caracteres del UUID en mayusculas. */
    static String number(UUID orderId) {
        return "#" + orderId.toString().substring(0, 8).toUpperCase();
    }
}
