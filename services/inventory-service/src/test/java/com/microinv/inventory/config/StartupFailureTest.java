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

/**
 * El arranque debe FALLAR con un mensaje claro si falta o es corto JWT_SECRET, o si falta INTERNAL_API_KEY.
 * Usa el application.yml real (sin perfil de prueba): demuestra que no hay valores por defecto inseguros.
 */
class StartupFailureTest {

    private static final String H2 = "jdbc:h2:mem:startup-failure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private static final String GOOD_SECRET = "0123456789abcdef0123456789abcdef";

    private static void run(String... properties) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=" + H2, "--spring.datasource.username=sa", "--spring.datasource.password="));
        for (String property : properties) {
            args.add("--" + property); // argumentos de linea de comandos: ganan al application.yml
        }
        new SpringApplicationBuilder(InventoryApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new))
                .close();
    }

    /** Control: con la configuracion completa y valida SI arranca (asi los fallos de arriba son por la config). */
    @Test
    void startsWithAValidConfiguration() {
        assertThatCode(() -> run("app.security.jwt-secret=" + GOOD_SECRET, "app.security.internal-api-key=some-key"))
                .doesNotThrowAnyException();
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
                .satisfies(e -> assertThat(rootMessages(e)).contains("JWT_SECRET must be set"));
    }

    @Test
    void failsWhenJwtSecretIsTooShort() {
        assertThatThrownBy(() -> run("app.security.jwt-secret=short", "app.security.internal-api-key=some-key"))
                .satisfies(e -> assertThat(rootMessages(e)).contains("at least 32 characters")
                        .doesNotContain("=short"));
    }

    @Test
    void failsWhenInternalApiKeyIsMissing() {
        assertThatThrownBy(() -> run("app.security.jwt-secret=" + GOOD_SECRET))
                .satisfies(e -> assertThat(rootMessages(e)).contains("INTERNAL_API_KEY must be set"));
    }
}
