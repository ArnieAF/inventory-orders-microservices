package com.microinv.inventory.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Presenta el fallo de configuracion como el bloque "APPLICATION FAILED TO START" de Spring Boot. */
public class StartupConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<StartupConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, StartupConfigurationException cause) {
        return new FailureAnalysis(cause.getMessage(),
                "Set the required environment variables (JWT_SECRET with at least 32 characters, INTERNAL_API_KEY) "
                        + "and restart the service. See docs/CONTRACT.md, section 8.",
                cause);
    }
}
