package com.microinv.orders.config;

/**
 * Configuracion obligatoria ausente o invalida. El mensaje nombra las variables de entorno afectadas
 * pero NUNCA incluye sus valores (un secreto corto no debe acabar en los logs de arranque).
 */
public class InvalidConfigurationException extends RuntimeException {

    public InvalidConfigurationException(String message) {
        super(message);
    }
}
