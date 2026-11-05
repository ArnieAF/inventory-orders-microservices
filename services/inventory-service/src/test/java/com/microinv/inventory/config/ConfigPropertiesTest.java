package com.microinv.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ConfigPropertiesTest {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsMinimumValidSecurityConfig() {
        SecurityProperties p = new SecurityProperties(SECRET_32, "micro-inventory", "key");
        assertThat(p.jwtSecret()).hasSize(32);
    }

    @Test
    void rejectsMissingOrShortJwtSecret() {
        assertThatThrownBy(() -> new SecurityProperties(null, "micro-inventory", "key"))
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new SecurityProperties("", "micro-inventory", "key"))
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new SecurityProperties(SECRET_32.substring(0, 31), "micro-inventory", "key"))
                .hasMessageContaining("at least 32");
        assertThatThrownBy(() -> new SecurityProperties(" ".repeat(40), "micro-inventory", "key"))
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsMissingInternalApiKey() {
        assertThatThrownBy(() -> new SecurityProperties(SECRET_32, "micro-inventory", null))
                .hasMessageContaining("INTERNAL_API_KEY");
        assertThatThrownBy(() -> new SecurityProperties(SECRET_32, "micro-inventory", "  "))
                .hasMessageContaining("INTERNAL_API_KEY");
    }

    @Test
    void rejectsBlankIssuer() {
        assertThatThrownBy(() -> new SecurityProperties(SECRET_32, " ", "key")).hasMessageContaining("JWT_ISSUER");
    }

    @Test
    void toStringNeverPrintsSecrets() {
        String text = new SecurityProperties(SECRET_32, "micro-inventory", "super-internal-key").toString();
        assertThat(text).doesNotContain(SECRET_32).doesNotContain("super-internal-key");
    }

    @Test
    void notificationTimeoutsDefaultToContractValues() {
        // Los valores por defecto se aplican via @DefaultValue; el application.yml los fija igual (2 s / 5 s).
        NotificationProperties p = new NotificationProperties("http://notification-service:8084",
                Duration.ofSeconds(2), Duration.ofSeconds(5), true);
        assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsInvalidNotificationConfig() {
        Duration ok = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new NotificationProperties("", ok, ok, true)).hasMessageContaining("NOTIFICATION");
        assertThatThrownBy(() -> new NotificationProperties("ftp://host", ok, ok, true))
                .hasMessageContaining("http(s)");
        assertThatThrownBy(() -> new NotificationProperties("http://host", Duration.ZERO, ok, true))
                .hasMessageContaining("positive");
    }
}
