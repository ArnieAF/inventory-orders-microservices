package com.microinv.orders.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion de la aplicacion. Se valida al construirla: si falta {@code JWT_SECRET} (o mide menos de 32
 * caracteres) o falta {@code INTERNAL_API_KEY}, el arranque falla con un mensaje claro y sin mostrar valores.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue Internal internal,
        @DefaultValue Inventory inventory,
        @DefaultValue Notifications notifications) {

    public static final int MIN_JWT_SECRET_LENGTH = 32;
    /** Contrato, seccion 8: INTERNAL_API_KEY de al menos 16 caracteres. */
    public static final int MIN_INTERNAL_API_KEY_LENGTH = 16;

    public record Jwt(String secret, @DefaultValue("micro-inventory") String issuer) {
        public Jwt {
            if (secret == null || secret.length() < MIN_JWT_SECRET_LENGTH) {
                throw new InvalidConfigurationException(
                        "JWT_SECRET is missing or shorter than " + MIN_JWT_SECRET_LENGTH + " characters.");
            }
            if (issuer == null || issuer.isBlank()) {
                throw new InvalidConfigurationException("JWT_ISSUER must not be blank.");
            }
        }
    }

    public record Internal(String apiKey) {
        public Internal {
            if (apiKey == null || apiKey.isBlank() || apiKey.length() < MIN_INTERNAL_API_KEY_LENGTH) {
                throw new InvalidConfigurationException(
                        "INTERNAL_API_KEY is missing or shorter than " + MIN_INTERNAL_API_KEY_LENGTH + " characters.");
            }
            if (apiKey.chars().anyMatch(Character::isISOControl)) {
                throw new InvalidConfigurationException("INTERNAL_API_KEY contains control characters.");
            }
        }
    }

    public record Inventory(
            @DefaultValue("http://inventory-service:8082") String baseUrl,
            @DefaultValue("2000") int connectTimeoutMs,
            @DefaultValue("5000") int readTimeoutMs) {
        public Inventory {
            requireHttpUrl(baseUrl, "INVENTORY_SERVICE_URL");
            requireTimeout(connectTimeoutMs, "app.inventory.connect-timeout-ms");
            requireTimeout(readTimeoutMs, "app.inventory.read-timeout-ms");
        }
    }

    public record Notifications(
            @DefaultValue("http://notification-service:8084") String baseUrl,
            @DefaultValue("2000") int connectTimeoutMs,
            @DefaultValue("5000") int readTimeoutMs,
            @DefaultValue("true") boolean async) {
        public Notifications {
            requireHttpUrl(baseUrl, "NOTIFICATION_SERVICE_URL");
            requireTimeout(connectTimeoutMs, "app.notifications.connect-timeout-ms");
            requireTimeout(readTimeoutMs, "app.notifications.read-timeout-ms");
        }
    }

    static void requireHttpUrl(String url, String variable) {
        boolean valid = false;
        if (url != null && !url.isBlank()) {
            try {
                URI uri = URI.create(url);
                valid = uri.getHost() != null
                        && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
            } catch (IllegalArgumentException ignored) {
                valid = false;
            }
        }
        if (!valid) {
            throw new InvalidConfigurationException(variable + " must be a valid http(s) URL.");
        }
    }

    static void requireTimeout(int millis, String property) {
        if (millis < 1 || millis > 60_000) {
            throw new InvalidConfigurationException(property + " must be between 1 and 60000.");
        }
    }
}
