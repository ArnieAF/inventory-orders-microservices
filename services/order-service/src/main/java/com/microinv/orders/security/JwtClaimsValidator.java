package com.microinv.orders.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Exige lo que el contrato garantiza en un token emitido por auth-service y que el validador por defecto
 * no comprueba: {@code exp} presente (un token sin expiracion seria eterno), {@code sub} UUID y
 * {@code username} utilizable.
 */
public class JwtClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "Invalid token", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        boolean valid = jwt.getExpiresAt() != null
                && CurrentUser.parseId(jwt.getSubject()) != null
                && jwt.getClaim("username") instanceof String username
                && CurrentUser.isValidUsername(username);
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(INVALID);
    }
}
