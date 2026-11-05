package com.microinv.orders.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** El arranque debe fallar con un mensaje claro (y sin mostrar el secreto) si falta configuracion obligatoria. */
class AppPropertiesTest {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class PropsOnly {
    }

    /** Lee el application.yml real, de modo que se prueba tambien la correspondencia JWT_SECRET -> app.jwt.secret. */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropsOnly.class);
    }

    private static String chain(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    // ---- valores directos -------------------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "0123456789abcdef0123456789abcde"}) // 31 caracteres
    void jwtSecretMissingOrShorterThan32Fails(String secret) {
        assertThatThrownBy(() -> new AppProperties.Jwt(secret, "micro-inventory"))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("32");
    }

    @Test
    void jwtSecretOfExactly32CharactersIsAccepted() {
        assertThatCode(() -> new AppProperties.Jwt(SECRET_32, "micro-inventory")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void internalApiKeyMissingOrBlankFails(String key) {
        assertThatThrownBy(() -> new AppProperties.Internal(key))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("INTERNAL_API_KEY");
    }

    @Test
    void internalApiKeyWithControlCharactersFailsBecauseItWouldBreakEveryCall() {
        assertThatThrownBy(() -> new AppProperties.Internal("abc\r\nX-Evil: 1"))
                .isInstanceOf(InvalidConfigurationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"inventory-service:8082", "ftp://inventory:8082", "http://", "not a url"})
    void serviceUrlsMustBeHttpUrls(String url) {
        assertThatThrownBy(() -> new AppProperties.Inventory(url, 2000, 5000))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("INVENTORY_SERVICE_URL");
        assertThatThrownBy(() -> new AppProperties.Notifications(url, 2000, 5000, true))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("NOTIFICATION_SERVICE_URL");
    }

    @Test
    void timeoutsMustBePositiveAndBounded() {
        assertThatThrownBy(() -> new AppProperties.Inventory("http://inventory-service:8082", 0, 5000))
                .isInstanceOf(InvalidConfigurationException.class);
        assertThatThrownBy(() -> new AppProperties.Inventory("http://inventory-service:8082", 2000, 60_001))
                .isInstanceOf(InvalidConfigurationException.class);
    }

    // ---- arranque real (application.yml + variables de entorno) ----------------------------------------------

    @Test
    void startupFailsWithClearMessageWhenJwtSecretIsMissing() {
        runner().withSystemProperties("INTERNAL_API_KEY=some-internal-key").run(context -> {
            assertThat(context).hasFailed();
            assertThat(chain(context.getStartupFailure())).contains("JWT_SECRET is missing or shorter than 32");

            FailureAnalysis analysis = new InvalidConfigurationFailureAnalyzer()
                    .analyze(context.getStartupFailure());
            assertThat(analysis).isNotNull();
            assertThat(analysis.getDescription()).contains("JWT_SECRET");
            assertThat(analysis.getAction()).contains("environment variables");
        });
    }

    @Test
    void startupFailsWithoutEverPrintingAShortSecret() {
        runner().withSystemProperties("JWT_SECRET=too-short-secret-value", "INTERNAL_API_KEY=some-internal-key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(chain(context.getStartupFailure())).contains("JWT_SECRET")
                            .doesNotContain("too-short-secret-value");
                });
    }

    @Test
    void startupFailsWithClearMessageWhenInternalApiKeyIsMissing() {
        runner().withSystemProperties("JWT_SECRET=" + SECRET_32).run(context -> {
            assertThat(context).hasFailed();
            assertThat(chain(context.getStartupFailure())).contains("INTERNAL_API_KEY is missing");
            assertThat(chain(context.getStartupFailure())).doesNotContain(SECRET_32);
        });
    }

    @Test
    void startsWithBothSecretsAndAppliesContractDefaults() {
        runner().withSystemProperties("JWT_SECRET=" + SECRET_32, "INTERNAL_API_KEY=some-internal-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppProperties props = context.getBean(AppProperties.class);
                    assertThat(props.jwt().secret()).isEqualTo(SECRET_32);
                    assertThat(props.jwt().issuer()).isEqualTo("micro-inventory");
                    assertThat(props.internal().apiKey()).isEqualTo("some-internal-key");
                    assertThat(props.inventory().baseUrl()).isEqualTo("http://inventory-service:8082");
                    assertThat(props.inventory().connectTimeoutMs()).isEqualTo(2000);
                    assertThat(props.inventory().readTimeoutMs()).isEqualTo(5000);
                    assertThat(props.notifications().baseUrl()).isEqualTo("http://notification-service:8084");
                    assertThat(props.notifications().connectTimeoutMs()).isEqualTo(2000);
                    assertThat(props.notifications().readTimeoutMs()).isEqualTo(5000);
                    assertThat(props.notifications().async()).isTrue();
                });
    }

    @Test
    void serviceUrlsAndIssuerComeFromTheEnvironment() {
        runner().withSystemProperties("JWT_SECRET=" + SECRET_32, "INTERNAL_API_KEY=k",
                        "JWT_ISSUER=other-issuer", "INVENTORY_SERVICE_URL=http://inv:9000",
                        "NOTIFICATION_SERVICE_URL=http://notif:9001")
                .run(context -> {
                    AppProperties props = context.getBean(AppProperties.class);
                    assertThat(props.jwt().issuer()).isEqualTo("other-issuer");
                    assertThat(props.inventory().baseUrl()).isEqualTo("http://inv:9000");
                    assertThat(props.notifications().baseUrl()).isEqualTo("http://notif:9001");
                });
    }
}
