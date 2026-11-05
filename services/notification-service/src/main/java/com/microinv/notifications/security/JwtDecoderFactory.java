package com.microinv.notifications.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Construye el decodificador de JWT HS256 que comparten REST y WebSocket (§3): firma con {@code JWT_SECRET}
 * (bytes UTF-8), {@code iss} igual a {@code JWT_ISSUER}, {@code exp} obligatorio y vigente y {@code sub} con
 * forma de UUID. Cualquier otro algoritmo (incluido {@code none}) se rechaza.
 */
public final class JwtDecoderFactory {

    private JwtDecoderFactory() {
    }

    public static JwtDecoder create(String secret, String issuer) {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(validator(issuer));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer) {
        // createDefaultWithIssuer = vigencia (exp/nbf) + emisor; añadimos exp y sub obligatorios.
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuer);
        return jwt -> {
            OAuth2TokenValidatorResult result = defaults.validate(jwt);
            if (result.hasErrors()) {
                return result;
            }
            return requiredClaims(jwt);
        };
    }

    private static OAuth2TokenValidatorResult requiredClaims(Jwt jwt) {
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null) {
            return failure("The token has no expiration");
        }
        String subject = jwt.getSubject();
        if (subject == null || !isUuid(subject)) {
            return failure("The token subject is not a valid user id");
        }
        return OAuth2TokenValidatorResult.success();
    }

    /** UUID en forma canónica (8-4-4-4-12); {@link UUID#fromString} por sí solo es demasiado permisivo. */
    static boolean isUuid(String value) {
        if (value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
