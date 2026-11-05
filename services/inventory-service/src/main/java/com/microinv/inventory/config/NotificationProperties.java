package com.microinv.inventory.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cliente hacia notification-service (contrato 3: timeouts de conexion 2 s y lectura 5 s).
 *
 * @param async si es {@code true} (produccion) el envio se hace en un hilo aparte, para que la
 *              latencia de notification-service nunca retrase la respuesta de una reserva.
 */
@ConfigurationProperties(prefix = "app.notifications")
public record NotificationProperties(
        String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("true") boolean async) {

    public NotificationProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("NOTIFICATION_SERVICE_URL must be set");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("NOTIFICATION_SERVICE_URL is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("NOTIFICATION_SERVICE_URL must be an http(s) URL");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Notification timeouts must be positive");
        }
        baseUrl = baseUrl.trim();
    }
}
