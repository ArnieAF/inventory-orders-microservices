package com.microinv.notifications.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.notifications.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtDecoderFactoryTest {

    private final JwtDecoder decoder = JwtDecoderFactory.create(TestTokens.SECRET, TestTokens.ISSUER);
    private final UUID userId = UUID.randomUUID();

    @Test
    void acceptsAValidToken() {
        Jwt jwt = decoder.decode(TestTokens.valid(userId));
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(CurrentUser.id(jwt)).isEqualTo(userId);
    }

    @Test
    void rejectsExpiredTokens() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.expired(userId))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokensSignedWithAnotherSecret() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.signedWithOtherSecret(userId)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTamperedPayload() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.tampered(userId, UUID.randomUUID())))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnsignedTokens() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.unsigned(userId))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsOtherHmacAlgorithms() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.hs512(userId))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAWrongIssuer() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.wrongIssuer(userId))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokensWithoutExpiration() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.withoutExpiration(userId)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsSubjectsThatAreNotUuids() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.withSubject("not-a-uuid"))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(TestTokens.withSubject("1-1-1-1-1"))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(TestTokens.withSubject(""))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("a.b.c")).isInstanceOf(JwtException.class);
    }

    @Test
    void uuidCheckIsStrict() {
        assertThat(JwtDecoderFactory.isUuid(userId.toString())).isTrue();
        assertThat(JwtDecoderFactory.isUuid(userId.toString().toUpperCase())).isTrue();
        assertThat(JwtDecoderFactory.isUuid("1-1-1-1-1")).isFalse();
        assertThat(JwtDecoderFactory.isUuid(userId + "0")).isFalse();
        assertThat(JwtDecoderFactory.isUuid("")).isFalse();
    }
}
