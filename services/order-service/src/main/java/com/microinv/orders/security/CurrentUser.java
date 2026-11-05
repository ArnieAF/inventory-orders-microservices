package com.microinv.orders.security;

import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Usuario actual. Sale SIEMPRE de los claims {@code sub} (UUID) y {@code username} del JWT ya validado:
 * nunca del body ni de headers de la peticion.
 */
public record CurrentUser(UUID id, String username) {

    public static final int MAX_USERNAME_LENGTH = 64;

    public static CurrentUser from(Jwt jwt) {
        UUID id = parseId(jwt.getSubject());
        String username = jwt.getClaimAsString("username");
        if (id == null || !isValidUsername(username)) {
            throw new BadCredentialsException("Invalid token claims");
        }
        return new CurrentUser(id, username);
    }

    /** Devuelve el UUID canonico o {@code null} si el valor no lo es. */
    static UUID parseId(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean isValidUsername(String username) {
        return username != null
                && !username.isBlank()
                && username.length() <= MAX_USERNAME_LENGTH
                && username.chars().noneMatch(Character::isISOControl);
    }
}
