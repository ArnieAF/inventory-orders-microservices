package com.microinv.notifications.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * El arranque real (SpringApplication) falla ANTES de crear el contexto, con un mensaje claro y sin valores, si
 * falta configuración imprescindible. Así el error no queda oculto detrás de otro (por ejemplo, la base de datos).
 * Los valores se pasan como argumentos de línea de comandos (máxima prioridad, por encima de application.yml).
 */
class StartupValidationTest {

    private static final String SECRET = "startup-secret-startup-secret-1234567890";

    @Configuration
    static class EmptyApplication {
    }

    private static ConfigurableApplicationContext run(String... args) {
        return new SpringApplicationBuilder(EmptyApplication.class).web(WebApplicationType.NONE).run(args);
    }

    @Test
    void missingJwtSecretStopsStartupWithAClearFailure() {
        assertThatThrownBy(() -> run("--app.internal-api-key=k"))
                .isInstanceOf(ConfigurationValidationException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void shortJwtSecretStopsStartupWithoutEchoingIt() {
        String shortSecret = "only-twenty-characters";
        assertThatThrownBy(() -> run("--app.jwt.secret=" + shortSecret, "--app.internal-api-key=k"))
                .isInstanceOf(ConfigurationValidationException.class)
                .hasMessageContaining("JWT_SECRET")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(shortSecret));
    }

    @Test
    void missingInternalKeyStopsStartup() {
        assertThatThrownBy(() -> run("--app.jwt.secret=" + SECRET))
                .isInstanceOf(ConfigurationValidationException.class)
                .hasMessageContaining("INTERNAL_API_KEY");
    }

    @Test
    void wildcardOriginsStopStartup() {
        assertThatThrownBy(() -> run("--app.jwt.secret=" + SECRET, "--app.internal-api-key=k",
                "--app.websocket.allowed-origins=*"))
                .isInstanceOf(ConfigurationValidationException.class)
                .hasMessageContaining("ALLOWED_ORIGINS");
    }

    @Test
    void validConfigurationStarts() {
        try (ConfigurableApplicationContext context = run("--app.jwt.secret=" + SECRET, "--app.internal-api-key=k")) {
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    void theFailureIsPresentedWithTheMessageAndAnAction() {
        ConfigurationValidationException failure = new ConfigurationValidationException("JWT_SECRET is missing");
        FailureAnalysis analysis = new ConfigurationValidationFailureAnalyzer().analyze(failure);
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo("JWT_SECRET is missing");
        assertThat(analysis.getAction()).contains("environment variable");
        // Un fallo envuelto (p. ej. dentro de un BeanCreationException) también se reconoce.
        assertThat(new ConfigurationValidationFailureAnalyzer()
                .analyze(new RuntimeException("wrapper", failure))).isNotNull();
    }
}
