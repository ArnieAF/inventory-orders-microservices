package com.microinv.orders;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Fabrica de JWT de prueba firmados como lo haria auth-service (HS256, secreto en UTF-8). */
public final class TestTokens {

    /** Valor de prueba (el mismo de application-test.yml); no es un secreto real. */
    public static final String SECRET = "test-only-jwt-secret-0123456789-abcdefghijklmnop";
    public static final String ISSUER = "micro-inventory";

    private TestTokens() {
    }

    public static String valid(UUID userId, String username) {
        return sign(claims(userId.toString(), username, ISSUER, Instant.now().plusSeconds(3600)).build(),
                JWSAlgorithm.HS256, SECRET);
    }

    public static JWTClaimsSet.Builder claims(String sub, String username, String issuer, Instant expiresAt) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(sub)
                .issueTime(new Date())
                .claim("username", username);
        if (expiresAt != null) {
            builder.expirationTime(Date.from(expiresAt));
        }
        return builder;
    }

    public static String sign(JWTClaimsSet claims, JWSAlgorithm algorithm, String secret) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Token sin firma ({@code alg: none}). */
    public static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }
}
