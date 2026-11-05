package com.microinv.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametros de seguridad (contrato 3 y 8). Se validan al construirse: si falta {@code JWT_SECRET}
 * (o tiene menos de 32 caracteres) o falta {@code INTERNAL_API_KEY}, el arranque FALLA con un
 * mensaje claro. No hay valores por defecto inseguros.
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(String jwtSecret, String jwtIssuer, String internalApiKey) {

    public static final int MIN_JWT_SECRET_LENGTH = 32;

    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "JWT_SECRET must be set and have at least " + MIN_JWT_SECRET_LENGTH + " characters");
        }
        if (jwtIssuer == null || jwtIssuer.isBlank()) {
            throw new IllegalArgumentException("JWT_ISSUER must not be blank");
        }
        if (internalApiKey == null || internalApiKey.isBlank()) {
            throw new IllegalArgumentException("INTERNAL_API_KEY must be set");
        }
    }

    /** Nunca imprime los secretos. */
    @Override
    public String toString() {
        return "SecurityProperties[jwtSecret=<hidden>, jwtIssuer=" + jwtIssuer + ", internalApiKey=<hidden>]";
    }
}
