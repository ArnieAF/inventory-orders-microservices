package com.microinv.gateway.config;

/** Configuracion JWT invalida: el gateway no debe arrancar. El mensaje nunca incluye el secreto. */
public class InvalidJwtConfigurationException extends RuntimeException {

    public InvalidJwtConfigurationException(String message) {
        super(message);
    }
}
