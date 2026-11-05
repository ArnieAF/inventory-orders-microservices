package com.microinv.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.gateway.config.InvalidJwtConfigurationException;
import com.microinv.gateway.config.JwtConfig;
import com.microinv.gateway.config.JwtProperties;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Validacion pura de la configuracion JWT del gateway. */
class JwtConfigTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "      ", "\t\n"})
    void secretoAusenteOEnBlanco_seRechaza(String secret) {
        assertThatThrownBy(() -> JwtConfig.buildKey(new JwtProperties(secret, "micro-inventory")))
                .isInstanceOf(InvalidJwtConfigurationException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void secretoDe31_seRechaza_yDe32_seAcepta() {
        String s31 = "k".repeat(31);
        assertThatThrownBy(() -> JwtConfig.buildKey(new JwtProperties(s31, "micro-inventory")))
                .isInstanceOf(InvalidJwtConfigurationException.class)
                .hasMessageContaining("32")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(s31));
        SecretKey key = JwtConfig.buildKey(new JwtProperties("k".repeat(32), "micro-inventory"));
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void emisorVacio_seRechaza() {
        assertThatThrownBy(() -> JwtConfig.buildKey(new JwtProperties("k".repeat(40), " ")))
                .isInstanceOf(InvalidJwtConfigurationException.class);
    }

    @Test
    void toStringNuncaImprimeElSecreto() {
        assertThat(new JwtProperties("super-secret-value-that-must-not-leak-1234", "micro-inventory").toString())
                .doesNotContain("super-secret-value");
    }
}
