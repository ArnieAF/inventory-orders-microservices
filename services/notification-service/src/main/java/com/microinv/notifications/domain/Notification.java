package com.microinv.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Notificación persistida. {@code targetUserId == null} significa difusión (visible para todos). */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 30)
    private NotificationType type;

    @Column(name = "title", nullable = false, updatable = false, length = 120)
    private String title;

    @Column(name = "message", nullable = false, updatable = false, length = 500)
    private String message;

    @Column(name = "target_user_id", updatable = false)
    private UUID targetUserId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, updatable = false, length = 20)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
        // requerido por JPA
    }

    public Notification(UUID id, NotificationType type, String title, String message, UUID targetUserId,
                        UUID actorUserId, EntityType entityType, UUID entityId, Instant createdAt) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.message = message;
        this.targetUserId = targetUserId;
        this.actorUserId = actorUserId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public UUID getTargetUserId() {
        return targetUserId;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public EntityType getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
