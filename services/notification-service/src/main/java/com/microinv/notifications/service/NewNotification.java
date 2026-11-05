package com.microinv.notifications.service;

import com.microinv.notifications.domain.EntityType;
import com.microinv.notifications.domain.NotificationType;
import java.util.UUID;

/** Datos ya validados para crear una notificación. {@code targetUserId == null} = difusión. */
public record NewNotification(
        NotificationType type,
        String title,
        String message,
        UUID targetUserId,
        UUID actorUserId,
        EntityType entityType,
        UUID entityId) {
}
