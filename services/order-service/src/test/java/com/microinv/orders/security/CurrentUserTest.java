package com.microinv.orders.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class CurrentUserTest {

    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("t").header("alg", "HS256").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void userComesFromSubAndUsernameClaims() {
        CurrentUser user = CurrentUser.from(jwt().subject(ID.toString()).claim("username", "alice").build());
        assertThat(user.id()).isEqualTo(ID);
        assertThat(user.username()).isEqualTo("alice");
    }

    @Test
    void uppercaseUuidIsAccepted() {
        assertThat(CurrentUser.from(jwt().subject(ID.toString().toUpperCase()).claim("username", "a").build()).id())
                .isEqualTo(ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"alice", "1-1-1-1-1", "11111111111141118111111111111111", "not-a-uuid"})
    void nonCanonicalOrMissingSubIsRejected(String sub) {
        assertThat(CurrentUser.parseId(sub)).isNull();
    }

    @Test
    void fromRejectsTokensWithoutUsableClaims() {
        assertThatThrownBy(() -> CurrentUser.from(jwt().subject("nope").claim("username", "alice").build()))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> CurrentUser.from(jwt().subject(ID.toString()).claim("other", "x").build()))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> CurrentUser.from(jwt().subject(ID.toString()).claim("username", " ").build()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void validatorChecksExpSubAndUsername() {
        JwtClaimsValidator validator = new JwtClaimsValidator();

        assertThat(validator.validate(jwt().subject(ID.toString()).claim("username", "alice").build()).hasErrors())
                .isFalse();
        assertThat(validator.validate(jwt().subject(ID.toString()).claim("username", "u".repeat(65)).build())
                .hasErrors()).isTrue();
        assertThat(validator.validate(jwt().subject(ID.toString()).claim("username", 42).build()).hasErrors())
                .isTrue();
        assertThat(validator.validate(jwt().subject("alice").claim("username", "alice").build()).hasErrors())
                .isTrue();
        OAuth2TokenValidatorResult noExp = validator.validate(Jwt.withTokenValue("t").header("alg", "HS256")
                .subject(ID.toString()).claim("username", "alice").issuedAt(Instant.now()).build());
        assertThat(noExp.hasErrors()).isTrue();
    }
}
