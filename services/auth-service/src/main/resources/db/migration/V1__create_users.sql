-- SQL portable (PostgreSQL 16 y H2 en modo PostgreSQL): sin jsonb, citext, serial ni gen_random_uuid().
-- El UUID se genera en Java; username y email se guardan SIEMPRE en minusculas (lo fuerza el CHECK).
CREATE TABLE users (
    id            UUID                     NOT NULL,
    username      VARCHAR(32)              NOT NULL,
    email         VARCHAR(120)             NOT NULL,
    password_hash VARCHAR(100)             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_username_lower CHECK (username = LOWER(username)),
    CONSTRAINT ck_users_email_lower CHECK (email = LOWER(email))
);
