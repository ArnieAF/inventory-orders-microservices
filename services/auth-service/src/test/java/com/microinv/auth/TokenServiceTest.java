package com.microinv.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.microinv.auth.config.JwtProperties;
import com.microinv.auth.security.TokenService;
import com.microinv.auth.user.UserAccount;
import com.microinv.auth.web.AuthResponse;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Emision de JWT: claims exactos del contrato y firma HS256 verificable. */
class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00.789Z");

    @Test
    void emiteClaimsDelContrato_yFirmaHs256() throws Exception {
        SecretKeySpec key = new SecretKeySpec(TestJwt.SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        TokenService service = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)),
                new JwtProperties(TestJwt.SECRET, "micro-inventory", 15), Clock.fixed(NOW, ZoneOffset.UTC));
        UUID id = UUID.randomUUID();

        AuthResponse res = service.issue(new UserAccount(id, "alice", "alice@example.com", "$2a$10$hash", NOW));

        assertThat(res.tokenType()).isEqualTo("Bearer");
        assertThat(res.expiresIn()).isEqualTo(900);
        assertThat(res.user().id()).isEqualTo(id);
        assertThat(res.user().username()).isEqualTo("alice");
        assertThat(res.user().email()).isEqualTo("alice@example.com");

        SignedJWT jwt = SignedJWT.parse(res.accessToken());
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.verify(new MACVerifier(TestJwt.SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("micro-inventory");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(id.toString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("username")).isEqualTo("alice");
        assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(Instant.parse("2026-11-05T12:00:00Z"));
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(Instant.parse("2026-11-05T12:15:00Z"));
        // Sin datos sensibles dentro del token.
        assertThat(jwt.getJWTClaimsSet().getClaims()).doesNotContainKeys("email", "password", "password_hash");
    }
}
