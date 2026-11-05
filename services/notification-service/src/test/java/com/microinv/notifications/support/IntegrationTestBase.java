package com.microinv.notifications.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base de las pruebas de integración: servidor real en puerto aleatorio + MockMvc, H2 en modo PostgreSQL con las
 * migraciones Flyway reales. Todas las subclases comparten el mismo contexto de Spring (mismas propiedades).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.jwt.secret=" + TestTokens.SECRET,
                "app.jwt.issuer=" + TestTokens.ISSUER,
                "app.internal-api-key=" + TestTokens.INTERNAL_KEY,
                "app.websocket.allowed-origins=http://localhost:3000,http://localhost:5173",
                "app.websocket.connect-timeout=1500ms"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from notification_reads");
        jdbc.update("delete from notifications");
    }

    /** Inserta una notificación directamente (fecha controlada para comprobar el orden). */
    protected UUID insert(UUID targetUserId, Instant createdAt) {
        return insert(targetUserId, "Title", "Message", createdAt);
    }

    protected UUID insert(UUID targetUserId, String title, String message, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into notifications (id, type, title, message, target_user_id, actor_user_id, "
                        + "entity_type, entity_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, "NEW_ORDER", title, message, targetUserId, null, "ORDER", UUID.randomUUID(),
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return id;
    }

    protected int countReads(UUID userId) {
        Integer count = jdbc.queryForObject("select count(*) from notification_reads where user_id = ?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    protected static String bearer(UUID userId) {
        return "Bearer " + TestTokens.valid(userId);
    }
}
