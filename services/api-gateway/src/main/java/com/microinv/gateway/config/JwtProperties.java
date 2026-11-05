package com.microinv.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuracion JWT del gateway: solo valida (el emisor es auth-service). Sin secreto por defecto. */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, String issuer) {

    @Override
    public String toString() {
        // Nunca imprimir el secreto.
        return "JwtProperties[issuer=" + issuer + "]";
    }
}
