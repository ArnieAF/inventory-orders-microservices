-- notification-service: esquema inicial (§6.4 del contrato).
-- SQL portable (H2 en modo PostgreSQL y PostgreSQL 16): sin jsonb, citext, gen_random_uuid() ni serial.
-- El UUID lo genera la aplicación.

CREATE TABLE notifications (
    id              UUID                     NOT NULL,
    type            VARCHAR(30)              NOT NULL,
    title           VARCHAR(120)             NOT NULL,
    message         VARCHAR(500)             NOT NULL,
    target_user_id  UUID                     NULL,
    actor_user_id   UUID                     NULL,
    entity_type     VARCHAR(20)              NOT NULL,
    entity_id       UUID                     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT ck_notifications_type
        CHECK (type IN ('NEW_ORDER', 'LOW_STOCK', 'ORDER_STATUS_CHANGED')),
    CONSTRAINT ck_notifications_entity_type
        CHECK (entity_type IN ('ORDER', 'PRODUCT'))
);

-- target_user_id NULL = difusión (visible para todos).
CREATE INDEX idx_notifications_created_at ON notifications (created_at);
CREATE INDEX idx_notifications_target_created ON notifications (target_user_id, created_at);

-- Lectura por usuario: una difusión leída por A sigue sin leer para B.
CREATE TABLE notification_reads (
    notification_id UUID                     NOT NULL,
    user_id         UUID                     NOT NULL,
    read_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_reads PRIMARY KEY (notification_id, user_id),
    CONSTRAINT fk_notification_reads_notification
        FOREIGN KEY (notification_id) REFERENCES notifications (id) ON DELETE CASCADE
);

CREATE INDEX idx_notification_reads_user ON notification_reads (user_id);
