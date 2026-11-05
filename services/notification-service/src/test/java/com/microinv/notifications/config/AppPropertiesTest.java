package com.microinv.notifications.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AppPropertiesTest {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(AppProperties.class)
    static class Config {
    }

    private static AppProperties valid() {
        AppProperties properties = new AppProperties();
        properties.getJwt().setSecret(SECRET_32);
        properties.setInternalApiKey("some-key");
        properties.getWebsocket().setAllowedOrigins(List.of("http://localhost:3000"));
        return properties;
    }

    @Test
    void validConfigurationPasses() {
        assertThatCode(() -> valid().validate()).doesNotThrowAnyException();
    }

    @Test
    void secretOf32CharactersIsTheMinimumAccepted() {
        AppProperties properties = valid();
        properties.getJwt().setSecret("a".repeat(31));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        properties.getJwt().setSecret("a".repeat(32));
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void missingOrBlankSecretFails() {
        AppProperties properties = valid();
        properties.getJwt().setSecret("");
        assertThatThrownBy(properties::validate).hasMessageContaining("JWT_SECRET");
        properties.getJwt().setSecret(null);
        assertThatThrownBy(properties::validate).hasMessageContaining("JWT_SECRET");
        properties.getJwt().setSecret(" ".repeat(40));
        assertThatThrownBy(properties::validate).hasMessageContaining("JWT_SECRET");
    }

    @Test
    void missingInternalKeyFails() {
        AppProperties properties = valid();
        properties.setInternalApiKey("");
        assertThatThrownBy(properties::validate).hasMessageContaining("INTERNAL_API_KEY");
        properties.setInternalApiKey("   ");
        assertThatThrownBy(properties::validate).hasMessageContaining("INTERNAL_API_KEY");
        properties.setInternalApiKey(null);
        assertThatThrownBy(properties::validate).hasMessageContaining("INTERNAL_API_KEY");
    }

    @Test
    void validationMessagesNeverIncludeTheValues() {
        AppProperties properties = valid();
        properties.getJwt().setSecret("short-secret-value");
        assertThatThrownBy(properties::validate).satisfies(
                ex -> assertThat(ex.getMessage()).doesNotContain("short-secret-value"));
    }

    @Test
    void startupFailsWithAClearMessageWhenTheSecretIsMissing() {
        runner.withPropertyValues("app.internal-api-key=k", "app.websocket.allowed-origins=http://localhost:3000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessages(context.getStartupFailure())).contains("JWT_SECRET");
                });
    }

    @Test
    void startupFailsWithAClearMessageWhenTheSecretIsShort() {
        runner.withPropertyValues("app.jwt.secret=too-short-secret-0123456789",
                        "app.internal-api-key=k", "app.websocket.allowed-origins=http://localhost:3000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String messages = rootMessages(context.getStartupFailure());
                    assertThat(messages).contains("JWT_SECRET").contains("at least 32");
                    assertThat(messages).doesNotContain("too-short-secret");
                });
    }

    @Test
    void startupFailsWithAClearMessageWhenTheInternalKeyIsMissing() {
        runner.withPropertyValues("app.jwt.secret=" + SECRET_32, "app.websocket.allowed-origins=http://localhost:3000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessages(context.getStartupFailure())).contains("INTERNAL_API_KEY");
                });
    }

    @Test
    void startupSucceedsWithValidConfigurationAndBindsOriginsAsList() {
        runner.withPropertyValues("app.jwt.secret=" + SECRET_32, "app.internal-api-key=k",
                        "app.websocket.allowed-origins=http://localhost:3000, http://localhost:5173")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppProperties properties = context.getBean(AppProperties.class);
                    assertThat(properties.getWebsocket().normalizedAllowedOrigins())
                            .containsExactly("http://localhost:3000", "http://localhost:5173");
                    assertThat(properties.getJwt().getIssuer()).isEqualTo("micro-inventory");
                    assertThat(properties.getWebsocket().getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
                });
    }

    @Test
    void emptyOriginsFailAtStartup() {
        AppProperties properties = valid();
        properties.getWebsocket().setAllowedOrigins(List.of());
        assertThatThrownBy(properties::validate).hasMessageContaining("ALLOWED_ORIGINS");
        properties.getWebsocket().setAllowedOrigins(List.of(" ", ""));
        assertThatThrownBy(properties::validate).hasMessageContaining("ALLOWED_ORIGINS");
    }

    @Test
    void connectTimeoutMustBePositive() {
        AppProperties properties = valid();
        properties.getWebsocket().setConnectTimeout(Duration.ZERO);
        assertThatThrownBy(properties::validate).hasMessageContaining("connect-timeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "http://*.example.com", "https://*", "ftp://example.com", "example.com",
            "http://example.com/app", "http://user@example.com", "http://example.com?x=1", "http://example.com#a",
            "not a url", "null", "://x"})
    void invalidOriginsAreRejected(String origin) {
        assertThatThrownBy(() -> AppProperties.normalizeOrigin(origin)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALLOWED_ORIGINS");
    }

    @Test
    void originsAreNormalized() {
        assertThat(AppProperties.normalizeOrigin("HTTP://LocalHost:3000")).isEqualTo("http://localhost:3000");
        assertThat(AppProperties.normalizeOrigin("http://localhost:3000/")).isEqualTo("http://localhost:3000");
        assertThat(AppProperties.normalizeOrigin("https://app.example.com:443")).isEqualTo("https://app.example.com");
        assertThat(AppProperties.normalizeOrigin("http://app.example.com:80")).isEqualTo("http://app.example.com");
        assertThat(AppProperties.normalizeOrigin("  https://app.example.com  ")).isEqualTo("https://app.example.com");
    }

    @Test
    void duplicatedOriginsCollapse() {
        AppProperties.WebSocket websocket = new AppProperties.WebSocket();
        websocket.setAllowedOrigins(List.of("http://localhost:3000", "http://LOCALHOST:3000/"));
        assertThat(websocket.normalizedAllowedOrigins()).containsExactly("http://localhost:3000");
    }

    /** Mensajes de toda la cadena de causas (el fallo de arranque llega envuelto por Spring). */
    private static String rootMessages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            all.append(current.getMessage()).append('\n');
            if (current.getCause() == current) {
                break;
            }
        }
        return all.toString();
    }
}
