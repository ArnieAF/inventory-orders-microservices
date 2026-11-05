package com.microinv.orders.service;

import com.microinv.orders.client.NotificationRequest;
import com.microinv.orders.client.NotificationType;
import java.util.UUID;

/**
 * Construye las notificaciones (contrato 6.4). Textos breves y en ingles; solo llevan el numero (prefijo del id)
 * y el estado del pedido: nada del cliente ni de sus notas.
 */
final class OrderNotifications {

    private OrderNotifications() {
    }

    static NotificationRequest newOrder(OrderCreatedEvent event) {
        return new NotificationRequest(
                NotificationType.NEW_ORDER,
                "New order",
                "Order " + number(event.orderId()) + " was created",
                null, // broadcast
                event.actorUserId(),
                NotificationRequest.ENTITY_ORDER,
                event.orderId());
    }

    static NotificationRequest statusChanged(OrderStatusChangedEvent event) {
        return new NotificationRequest(
                NotificationType.ORDER_STATUS_CHANGED,
                "Order status changed",
                "Order " + number(event.orderId()) + " changed from " + event.from() + " to " + event.to(),
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
