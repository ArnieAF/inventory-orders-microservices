package com.microinv.notifications.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/** Fabrica JWT de prueba (HS256) con la misma forma que los que emite auth-service (§3). Solo valores de prueba. */
public final class TestTokens {

    public static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    public static final String OTHER_SECRET = "another-secret-another-secret-another-secret-99";
    public static final String ISSUER = "micro-inventory";
    public static final String INTERNAL_KEY = "test-internal-key-for-unit-tests-only";

    private TestTokens() {
    }

    public static String valid(UUID userId) {
        return build(SECRET, ISSUER, userId.toString(), Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(3600), JWSAlgorithm.HS256);
    }

    public static String expired(UUID userId) {
        return build(SECRET, ISSUER, userId.toString(), Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600), JWSAlgorithm.HS256);
    }

    public static String signedWithOtherSecret(UUID userId) {
        return build(OTHER_SECRET, ISSUER, userId.toString(), Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(3600), JWSAlgorithm.HS256);
    }

    public static String wrongIssuer(UUID userId) {
        return build(SECRET, "someone-else", userId.toString(), Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(3600), JWSAlgorithm.HS256);
    }

    public static String withSubject(String subject) {
        return build(SECRET, ISSUER, subject, Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600),
                JWSAlgorithm.HS256);
    }

    public static String withoutExpiration(UUID userId) {
        return build(SECRET, ISSUER, userId.toString(), Instant.now().minusSeconds(5), null, JWSAlgorithm.HS256);
    }

    /** Firmado con HS512 (el servicio solo admite HS256). */
    public static String hs512(UUID userId) {
        return build(SECRET + SECRET, ISSUER, userId.toString(), Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(3600), JWSAlgorithm.HS512);
    }

    /** JWT sin firma ({@code alg: none}). */
    public static String unsigned(UUID userId) {
        JWTClaimsSet claims = claims(ISSUER, userId.toString(), Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(3600));
        return new PlainJWT(claims).serialize();
    }

    /** Token válido al que se le cambia el {@code sub} del payload conservando la firma original. */
    public static String tampered(UUID userId, UUID otherUserId) {
        String token = valid(userId);
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String changed = payload.replace(userId.toString(), otherUserId.toString());
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(changed.getBytes(StandardCharsets.UTF_8));
        return String.join(".", parts);
    }

    private static String build(String secret, String issuer, String subject, Instant issuedAt, Instant expiresAt,
                                JWSAlgorithm algorithm) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims(issuer, subject, issuedAt, expiresAt));
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build the test token", ex);
        }
    }

    private static JWTClaimsSet claims(String issuer, String subject, Instant issuedAt, Instant expiresAt) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .claim("username", "tester")
                .issueTime(Date.from(issuedAt));
        if (expiresAt != null) {
            builder.expirationTime(Date.from(expiresAt));
        }
        return builder.build();
    }
}
