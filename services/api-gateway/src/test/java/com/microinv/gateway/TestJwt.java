package com.microinv.gateway;

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

/** Utilidades de test para fabricar tokens validos, caducados o manipulados. */
public final class TestJwt {

    public static final String SECRET = "test-only-secret-do-not-use-in-prod-0123456789abcdef";
    public static final String ISSUER = "micro-inventory";

    private TestJwt() {
    }

    public static String mint(String secret, String issuer, String subject, String username, Instant iat, Instant exp) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issueTime(Date.from(iat))
                    .expirationTime(Date.from(exp));
            if (issuer != null) {
                claims.issuer(issuer);
            }
            if (subject != null) {
                claims.subject(subject);
            }
            if (username != null) {
                claims.claim("username", username);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String valid(String subject, String username) {
        Instant now = Instant.now();
        return mint(SECRET, ISSUER, subject, username, now, now.plusSeconds(600));
    }

    public static String expired(String subject, String username) {
        Instant now = Instant.now();
        return mint(SECRET, ISSUER, subject, username, now.minusSeconds(3600), now.minusSeconds(600));
    }

    /** Cambia el payload conservando la firma original: la firma deja de ser valida. */
    public static String tamperPayload(String token, String from, String to) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        if (!payload.contains(from)) {
            throw new IllegalArgumentException("payload does not contain " + from);
        }
        String changed = payload.replace(from, to);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(changed.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + encoded + "." + parts[2];
    }

    /** Token sin firma (alg=none). */
    public static String unsigned(String subject, String username) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long exp = Instant.now().plusSeconds(600).getEpochSecond();
        String payload = enc.encodeToString(("{\"iss\":\"" + ISSUER + "\",\"sub\":\"" + subject
                + "\",\"username\":\"" + username + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }
}
