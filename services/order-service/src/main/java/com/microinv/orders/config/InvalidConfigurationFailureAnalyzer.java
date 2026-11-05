package com.microinv.orders.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Muestra un mensaje de arranque claro (sin pila) cuando falta configuracion obligatoria. */
public class InvalidConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<InvalidConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidConfigurationException cause) {
        return new FailureAnalysis(
                cause.getMessage(),
                "Provide the missing environment variables (see docs/CONTRACT.md, section 8) and start the service again.",
                cause);
    }
}
