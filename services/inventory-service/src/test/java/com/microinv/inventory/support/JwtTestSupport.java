package com.microinv.inventory.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/** Fabrica JWT de prueba con la misma convencion que auth-service (HS256, iss, sub, username, iat, exp). */
public final class JwtTestSupport {

    private JwtTestSupport() {
    }

    public static String validToken() {
        return validToken(UUID.randomUUID().toString());
    }

    public static String validToken(String subject) {
        return token(TestSecrets.JWT_SECRET, JWSAlgorithm.HS256, TestSecrets.JWT_ISSUER, subject,
                Instant.now().plusSeconds(3600));
    }

    public static String token(String secret, JWSAlgorithm alg, String issuer, String subject, Instant expiresAt) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .claim("username", "tester")
                .issueTime(Date.from(Instant.now().minusSeconds(5)));
        if (issuer != null) {
            claims.issuer(issuer);
        }
        if (subject != null) {
            claims.subject(subject);
        }
        if (expiresAt != null) {
            claims.expirationTime(Date.from(expiresAt));
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(alg), claims.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Token con {@code "alg":"none"} y sin firma. */
    public static String unsignedToken() {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"iss\":\"" + TestSecrets.JWT_ISSUER + "\",\"sub\":\""
                + UUID.randomUUID() + "\",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }
}
