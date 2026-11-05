package com.microinv.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Clave compuesta de {@link NotificationRead}: (notification_id, user_id). */
@Embeddable
public class NotificationReadId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    protected NotificationReadId() {
        // requerido por JPA
    }

    public NotificationReadId(UUID notificationId, UUID userId) {
        this.notificationId = notificationId;
        this.userId = userId;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public UUID getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NotificationReadId other)) {
            return false;
        }
        return Objects.equals(notificationId, other.notificationId) && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(notificationId, userId);
    }
}
