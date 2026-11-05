package com.microinv.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.inventory.InventoryApplication;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * El arranque debe FALLAR con un mensaje claro si falta o es corto JWT_SECRET, o si falta INTERNAL_API_KEY.
 * Usa el application.yml real (sin perfil de prueba): demuestra que no hay valores por defecto inseguros.
 */
class StartupFailureTest {

    private static final String H2 = "jdbc:h2:mem:startup-failure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private static final String GOOD_SECRET = "0123456789abcdef0123456789abcdef";

    private static void run(boolean withDatasource, String... properties) {
        List<String> args = new ArrayList<>(List.of("--server.port=0"));
        if (withDatasource) {
            args.addAll(List.of("--spring.datasource.url=" + H2, "--spring.datasource.username=sa",
                    "--spring.datasource.password="));
        }
        for (String property : properties) {
            args.add("--" + property); // argumentos de linea de comandos: ganan al application.yml
        }
        new SpringApplicationBuilder(InventoryApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new))
                .close();
    }

    private static void run(String... properties) {
        run(true, properties);
    }

    private static String rootMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void failsWhenJwtSecretIsNotConfiguredAtAll() {
        assertThatThrownBy(() -> run("app.security.internal-api-key=some-key"))
                .isInstanceOf(StartupConfigurationException.class)
                .hasMessage("JWT_SECRET must be set and have at least 32 characters");
    }

    @Test
    void failsWhenJwtSecretIsTooShortWithoutEchoingIt() {
        assertThatThrownBy(() -> run("app.security.jwt-secret=short", "app.security.internal-api-key=some-key"))
                .isInstanceOf(StartupConfigurationException.class)
                .satisfies(e -> assertThat(rootMessages(e)).contains("at least 32 characters")
                        .doesNotContain("short"));
    }

    @Test
    void failsWhenInternalApiKeyIsMissing() {
        assertThatThrownBy(() -> run("app.security.jwt-secret=" + GOOD_SECRET))
                .isInstanceOf(StartupConfigurationException.class)
                .hasMessage("INTERNAL_API_KEY must be set");
    }

    @Test
    void securityErrorIsReportedFirstEvenIfTheDatabaseIsNotConfiguredEither() {
        // Sin SPRING_DATASOURCE_URL ni JWT_SECRET: debe salir el error de seguridad, no "Failed to configure a DataSource".
        assertThatThrownBy(() -> run(false))
                .isInstanceOf(StartupConfigurationException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void failureAnalyzerPrintsTheClearMessageAndAnAction() {
        StartupConfigurationException cause = new StartupConfigurationException("INTERNAL_API_KEY must be set");
        FailureAnalysis analysis = new StartupConfigurationFailureAnalyzer().analyze(cause);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo("INTERNAL_API_KEY must be set");
        assertThat(analysis.getAction()).contains("JWT_SECRET").contains("INTERNAL_API_KEY");
    }

    /** Control: con la configuracion completa y valida SI arranca (asi los fallos de arriba son por la config). */
    @Test
    void startsWithAValidConfiguration() {
        assertThatCode(() -> run("app.security.jwt-secret=" + GOOD_SECRET, "app.security.internal-api-key=some-key"))
                .doesNotThrowAnyException();
    }
}
