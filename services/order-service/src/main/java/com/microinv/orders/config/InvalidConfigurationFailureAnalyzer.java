package com.microinv.orders.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;

/**
 * Muestra un mensaje de arranque claro (sin pila) cuando falta configuracion obligatoria. Tiene la maxima
 * prioridad para que ningun otro analizador (p. ej. el de errores de binding, que imprime valores) lo sustituya.
 */
public class InvalidConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<InvalidConfigurationException>
        implements Ordered {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidConfigurationException cause) {
        return new FailureAnalysis(
                cause.getMessage(),
                "Provide the missing environment variables (see docs/CONTRACT.md, section 8) and start the service again.",
                cause);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
