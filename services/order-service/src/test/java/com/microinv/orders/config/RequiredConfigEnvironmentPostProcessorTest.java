package com.microinv.orders.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.orders.OrderServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

/** El servicio falla ANTES de crear beans (sin tocar la base de datos) si la configuracion obligatoria no es valida. */
class RequiredConfigEnvironmentPostProcessorTest {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";

    private final RequiredConfigEnvironmentPostProcessor processor = new RequiredConfigEnvironmentPostProcessor();

    private void process(MockEnvironment environment) {
        processor.postProcessEnvironment(environment, new SpringApplication());
    }

    @Test
    void acceptsAValidConfiguration() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.jwt.secret", SECRET_32)
                .withProperty("app.internal.api-key", "some-internal-key");
        assertThatCode(() -> process(env)).doesNotThrowAnyException();
    }

    @Test
    void failsWhenTheJwtSecretIsMissingEmptyOrShortWithoutEchoingIt() {
        MockEnvironment missing = new MockEnvironment().withProperty("app.internal.api-key", "some-internal-key");
        assertThatThrownBy(() -> process(missing)).isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("JWT_SECRET");

        MockEnvironment empty = new MockEnvironment().withProperty("app.jwt.secret", "")
                .withProperty("app.internal.api-key", "some-internal-key");
        assertThatThrownBy(() -> process(empty)).isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("JWT_SECRET");

        MockEnvironment shortSecret = new MockEnvironment().withProperty("app.jwt.secret", "short-secret-value")
                .withProperty("app.internal.api-key", "some-internal-key");
        assertThatThrownBy(() -> process(shortSecret)).isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("JWT_SECRET").hasMessageNotContaining("short-secret-value");
    }

    @Test
    void failsWhenTheInternalApiKeyIsMissingOrBlank() {
        MockEnvironment missing = new MockEnvironment().withProperty("app.jwt.secret", SECRET_32);
        assertThatThrownBy(() -> process(missing)).isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("INTERNAL_API_KEY");

        MockEnvironment blank = new MockEnvironment().withProperty("app.jwt.secret", SECRET_32)
                .withProperty("app.internal.api-key", "   ");
        assertThatThrownBy(() -> process(blank)).isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("INTERNAL_API_KEY");

        // contrato 8: al menos 16 caracteres, y el valor nunca se imprime
        MockEnvironment shortKey = new MockEnvironment().withProperty("app.jwt.secret", SECRET_32)
                .withProperty("app.internal.api-key", "only-15-chars-x");
        assertThatThrownBy(() -> process(shortKey)).isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("INTERNAL_API_KEY").hasMessageContaining("16")
                .hasMessageNotContaining("only-15-chars-x");
    }

    /**
     * Arranque real de SpringApplication con la configuracion por defecto del jar y una base de datos inalcanzable:
     * el error que sale es el de configuracion (no el de conexion), es decir, falla antes de tocar la BD.
     */
    @Test
    void realStartupFailsOnConfigurationBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(OrderServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run("--app.jwt.secret=far-too-short", "--app.internal.api-key=some-internal-key",
                        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/orders"))
                .satisfies(failure -> {
                    Throwable cause = failure;
                    while (cause != null && !(cause instanceof InvalidConfigurationException)) {
                        cause = cause.getCause();
                    }
                    assertThat(cause).as("InvalidConfigurationException in the cause chain").isNotNull();
                    assertThat(cause.getMessage()).contains("JWT_SECRET").doesNotContain("far-too-short");
                });
    }

    @Test
    void theClearMessageAnalyzerIsRegisteredAndWinsOverTheBindingAnalyzer() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer.class, getClass().getClassLoader()))
                .contains(InvalidConfigurationFailureAnalyzer.class.getName());
        assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(RequiredConfigEnvironmentPostProcessor.class.getName());

        InvalidConfigurationFailureAnalyzer analyzer = new InvalidConfigurationFailureAnalyzer();
        assertThat(analyzer.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        FailureAnalysis analysis = analyzer.analyze(new InvalidConfigurationException("JWT_SECRET is missing."));
        assertThat(analysis.getDescription()).isEqualTo("JWT_SECRET is missing.");
        assertThat(analysis.getAction()).contains("environment variables");
    }
}
