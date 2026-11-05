package com.microinv.notifications.api.dto;

import com.microinv.notifications.domain.EntityType;
import com.microinv.notifications.domain.Notification;
import com.microinv.notifications.domain.NotificationType;
import com.microinv.notifications.repository.NotificationView;
import java.time.Instant;
import java.util.UUID;

/** {@code Notification} del contrato (§6.4). Es el mismo JSON por REST y por WebSocket. */
public record NotificationResponse(
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

    public static NotificationResponse from(NotificationView view) {
        return new NotificationResponse(view.id(), view.type(), view.title(), view.message(), view.targetUserId(),
                view.actorUserId(), view.entityType(), view.entityId(), view.createdAt(), view.read());
    }

    public static NotificationResponse from(Notification notification, boolean read) {
        return new NotificationResponse(notification.getId(), notification.getType(), notification.getTitle(),
                notification.getMessage(), notification.getTargetUserId(), notification.getActorUserId(),
                notification.getEntityType(), notification.getEntityId(), notification.getCreatedAt(), read);
    }
}
