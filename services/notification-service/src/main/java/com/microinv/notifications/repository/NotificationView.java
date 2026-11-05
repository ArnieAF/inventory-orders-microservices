package com.microinv.notifications.repository;

import com.microinv.notifications.domain.EntityType;
import com.microinv.notifications.domain.NotificationType;
import java.time.Instant;
import java.util.UUID;

/** Fila de la consulta de historial: la notificación más el estado "leída" calculado para un usuario. */
public record NotificationView(
        UUID id,
        NotificationType type,
        String title,
        String message,
        UUID targetUserId,
        UUID actorUserId,
        EntityType entityType,
        UUID entityId,
        Instant createdAt,
        boolean read) {
}
