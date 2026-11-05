package com.microinv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.auth.config.InvalidJwtConfigurationException;
import com.microinv.auth.config.JwtConfig;
import com.microinv.auth.config.JwtProperties;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Logica pura de validacion de la configuracion JWT (sin Spring). */
class JwtConfigTest {

    private static final String ISSUER = "micro-inventory";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "      ", "\t\n"})
    void secretoAusenteOEnBlanco_seRechaza(String secret) {
        assertThatThrownBy(() -> JwtConfig.buildKey(new JwtProperties(secret, ISSUER, 60)))
                .isInstanceOf(InvalidJwtConfigurationException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void secretoDe31_seRechaza_yDe32_seAcepta() {
        String s31 = "k".repeat(31);
        assertThatThrownBy(() -> JwtConfig.buildKey(new JwtProperties(s31, ISSUER, 60)))
                .isInstanceOf(InvalidJwtConfigurationException.class)
                .hasMessageContaining("32")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(s31));

        SecretKey key = JwtConfig.buildKey(new JwtProperties("k".repeat(32), ISSUER, 60));
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).hasSize(32);
    }

    @Test
    void emisorVacioOExpiracionFueraDeRango_seRechazan() {
        String secret = "k".repeat(40);
        assertThatThrownBy(() -> JwtConfig.buildKey(new JwtProperties(secret, " ", 60)))
                .isInstanceOf(InvalidJwtConfigurationException.class);
        assertThatThrownBy(() -> JwtConfig.buildKey(new JwtProperties(secret, ISSUER, 0)))
                .isInstanceOf(InvalidJwtConfigurationException.class);
        assertThatThrownBy(() -> JwtConfig.buildKey(new JwtProperties(secret, ISSUER, -5)))
                .isInstanceOf(InvalidJwtConfigurationException.class);
        assertThatThrownBy(() -> JwtConfig.buildKey(new JwtProperties(secret, ISSUER, 10_081)))
                .isInstanceOf(InvalidJwtConfigurationException.class);
        assertThat(JwtConfig.buildKey(new JwtProperties(secret, ISSUER, 1))).isNotNull();
        assertThat(JwtConfig.buildKey(new JwtProperties(secret, ISSUER, 10_080))).isNotNull();
    }

    @Test
    void toStringNuncaImprimeElSecreto() {
        assertThat(new JwtProperties("super-secret-value-that-must-not-leak-1234", ISSUER, 60).toString())
                .doesNotContain("super-secret-value");
    }
}
