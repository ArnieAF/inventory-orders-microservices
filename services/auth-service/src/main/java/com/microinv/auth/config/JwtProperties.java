package com.microinv.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion JWT. El secreto llega solo por entorno (JWT_SECRET); jamas hay un valor por defecto.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, String issuer, int expirationMinutes) {

    @Override
    public String toString() {
        // Nunca imprimir el secreto.
        return "JwtProperties[issuer=" + issuer + ", expirationMinutes=" + expirationMinutes + "]";
    }
}
