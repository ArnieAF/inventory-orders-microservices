package com.microinv.auth.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Muestra un mensaje claro (en lugar de un stack trace) cuando falta o es debil JWT_SECRET. */
public class InvalidJwtConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<InvalidJwtConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidJwtConfigurationException cause) {
        return new FailureAnalysis(
                cause.getMessage(),
                "Define la variable de entorno JWT_SECRET con al menos "
                        + JwtConfig.MIN_SECRET_LENGTH + " caracteres aleatorios (no la guardes en el repositorio).",
                cause);
    }
}
