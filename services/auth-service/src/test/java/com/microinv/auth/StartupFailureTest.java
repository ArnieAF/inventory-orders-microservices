package com.microinv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.microinv.auth.config.InvalidJwtConfigurationException;
import com.microinv.auth.config.InvalidJwtConfigurationFailureAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** El servicio NO debe arrancar sin JWT_SECRET valido (>= 32 caracteres), con un mensaje claro. */
class StartupFailureTest {

    private static Throwable startWithSecret(String secretArg) {
        SpringApplication app = new SpringApplication(AuthServiceApplication.class);
        return catchThrowable(() -> app.run(
                "--server.port=0",
                "--spring.profiles.active=test",
                "--spring.datasource.url=jdbc:h2:mem:startupfail;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--app.jwt.secret=" + secretArg)
                .close());
    }

    private static InvalidJwtConfigurationException rootInvalidJwt(Throwable failure) {
        Throwable t = failure;
        while (t != null) {
            if (t instanceof InvalidJwtConfigurationException e) {
                return e;
            }
            t = t.getCause();
        }
        return null;
    }

    @Test
    void sinSecreto_elArranqueFalla_conMensajeClaro() {
        Throwable failure = startWithSecret(""); // JWT_SECRET ausente == cadena vacia (application.yml: ${JWT_SECRET:})
        assertThat(failure).isNotNull();
        InvalidJwtConfigurationException cause = rootInvalidJwt(failure);
        assertThat(cause).as("la causa debe ser InvalidJwtConfigurationException").isNotNull();
        assertThat(cause.getMessage()).contains("JWT_SECRET").contains("no esta definido");
    }

    @Test
    void secretoDe31Caracteres_elArranqueFalla_sinRevelarElSecreto() {
        String shortSecret = "x".repeat(31);
        Throwable failure = startWithSecret(shortSecret);
        InvalidJwtConfigurationException cause = rootInvalidJwt(failure);
        assertThat(cause).isNotNull();
        assertThat(cause.getMessage()).contains("JWT_SECRET").contains("32").doesNotContain(shortSecret);
    }

    @Test
    void secretoDe32Caracteres_arranca() {
        Throwable failure = startWithSecret("x".repeat(32));
        assertThat(failure).isNull();
    }

    @Test
    void elDiagnosticoDeArranqueExplicaQueHacer() {
        InvalidJwtConfigurationException ex = new InvalidJwtConfigurationException("JWT_SECRET no esta definido");
        FailureAnalysis analysis = new InvalidJwtConfigurationFailureAnalyzer().analyze(ex);
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("JWT_SECRET");
        assertThat(analysis.getAction()).contains("JWT_SECRET").contains("32");
    }
}
