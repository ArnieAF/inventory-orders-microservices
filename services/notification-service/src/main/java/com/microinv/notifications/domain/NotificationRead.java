package com.microinv.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Marca de "leída" de una notificación por un usuario concreto. */
@Entity
@Table(name = "notification_reads")
public class NotificationRead {

    @EmbeddedId
    private NotificationReadId id;

    @Column(name = "read_at", nullable = false, updatable = false)
    private Instant readAt;

    protected NotificationRead() {
        // requerido por JPA
    }

    public NotificationRead(UUID notificationId, UUID userId, Instant readAt) {
        this.id = new NotificationReadId(notificationId, userId);
        this.readAt = readAt;
    }

    public NotificationReadId getId() {
        return id;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
