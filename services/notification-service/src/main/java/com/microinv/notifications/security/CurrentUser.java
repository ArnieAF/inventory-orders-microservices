package com.microinv.notifications.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** El usuario actual sale siempre del claim {@code sub} del JWT validado, nunca de headers del cliente. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        // El validador de tokens ya garantiza que sub existe y es un UUID válido.
        return UUID.fromString(jwt.getSubject());
    }
}
