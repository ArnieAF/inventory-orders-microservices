package com.microinv.auth.config;

/** Configuracion JWT invalida: el servicio no debe arrancar. El mensaje nunca incluye el secreto. */
public class InvalidJwtConfigurationException extends RuntimeException {

    public InvalidJwtConfigurationException(String message) {
        super(message);
    }
}
