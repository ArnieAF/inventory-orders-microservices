package com.microinv.inventory.notification;

import java.util.UUID;

/**
 * Cuerpo de {@code POST /internal/notifications} (contrato 6.4). {@code targetUserId} nulo = difusion.
 * Los nulos se serializan explicitamente (el contrato los lista como campos del cuerpo).
 */
public record NotificationRequest(
        String type,
        String title,
        String message,
        UUID targetUserId,
        UUID actorUserId,
        String entityType,
        UUID entityId) {

    public static final String TYPE_LOW_STOCK = "LOW_STOCK";
    public static final String ENTITY_PRODUCT = "PRODUCT";
    public static final int MAX_TITLE = 120;
    public static final int MAX_MESSAGE = 500;
}
