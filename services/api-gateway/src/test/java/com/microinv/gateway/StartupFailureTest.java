package com.microinv.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.microinv.gateway.config.InvalidJwtConfigurationException;
import com.microinv.gateway.config.InvalidJwtConfigurationFailureAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** El gateway NO debe arrancar sin JWT_SECRET valido (>= 32 caracteres), con un mensaje claro. */
class StartupFailureTest {

    private static Throwable startWithSecret(String secret) {
        SpringApplication app = new SpringApplication(GatewayApplication.class);
        return catchThrowable(() -> app.run("--server.port=0", "--app.jwt.secret=" + secret).close());
    }

    private static InvalidJwtConfigurationException rootInvalidJwt(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof InvalidJwtConfigurationException e) {
                return e;
            }
        }
        return null;
    }

    @Test
    void sinSecreto_elArranqueFalla_conMensajeClaro() {
        Throwable failure = startWithSecret(""); // JWT_SECRET ausente == vacio (application.yml: ${JWT_SECRET:})
        InvalidJwtConfigurationException cause = rootInvalidJwt(failure);
        assertThat(cause).isNotNull();
        assertThat(cause.getMessage()).contains("JWT_SECRET").contains("no esta definido");
    }

    @Test
    void secretoDe31Caracteres_elArranqueFalla_sinRevelarElSecreto() {
        String shortSecret = "x".repeat(31);
        InvalidJwtConfigurationException cause = rootInvalidJwt(startWithSecret(shortSecret));
        assertThat(cause).isNotNull();
        assertThat(cause.getMessage()).contains("JWT_SECRET").contains("32").doesNotContain(shortSecret);
    }

    @Test
    void secretoDe32Caracteres_arranca() {
        assertThat(startWithSecret("x".repeat(32))).isNull();
    }

    @Test
    void emisorVacio_elArranqueFalla() {
        SpringApplication app = new SpringApplication(GatewayApplication.class);
        Throwable failure = catchThrowable(() -> app.run("--server.port=0", "--app.jwt.secret=" + "x".repeat(40),
                "--app.jwt.issuer=").close());
        InvalidJwtConfigurationException cause = rootInvalidJwt(failure);
        assertThat(cause).isNotNull();
        assertThat(cause.getMessage()).contains("JWT_ISSUER");
    }

    @Test
    void elDiagnosticoDeArranqueExplicaQueHacer() {
        FailureAnalysis analysis = new InvalidJwtConfigurationFailureAnalyzer()
                .analyze(new InvalidJwtConfigurationException("JWT_SECRET no esta definido"));
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("JWT_SECRET");
        assertThat(analysis.getAction()).contains("JWT_SECRET").contains("32");
    }
}
