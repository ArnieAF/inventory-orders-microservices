package com.microinv.orders.client;

import java.util.UUID;

/** Cuerpo de {@code POST /internal/notifications} (contrato 6.4). {@code targetUserId == null} = broadcast. */
public record NotificationRequest(
        NotificationType type,
        String title,
        String message,
        UUID targetUserId,
        UUID actorUserId,
        String entityType,
        UUID entityId) {

    public static final String ENTITY_ORDER = "ORDER";
}
