package com.microinv.notifications.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Presenta los errores de configuración como "APPLICATION FAILED TO START" con una acción concreta. */
public class ConfigurationValidationFailureAnalyzer extends AbstractFailureAnalyzer<ConfigurationValidationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ConfigurationValidationException cause) {
        return new FailureAnalysis(cause.getMessage(),
                "Set the missing or invalid environment variable (no default value is provided on purpose) "
                        + "and start the service again.",
                cause);
    }
}
