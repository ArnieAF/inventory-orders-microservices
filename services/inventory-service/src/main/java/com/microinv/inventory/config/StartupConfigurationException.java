package com.microinv.inventory.config;

/** Configuracion obligatoria ausente o invalida al arrancar. El mensaje nunca contiene valores secretos. */
public class StartupConfigurationException extends RuntimeException {

    public StartupConfigurationException(String message) {
        super(message);
    }
}
