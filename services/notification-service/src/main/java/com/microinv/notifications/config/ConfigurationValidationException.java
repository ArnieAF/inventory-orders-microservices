package com.microinv.notifications.config;

/**
 * Configuración imprescindible ausente o inválida. El mensaje es apto para mostrarse (y para el log) porque NUNCA
 * incluye el valor recibido, solo el nombre de la variable y la regla que incumple.
 */
public class ConfigurationValidationException extends IllegalStateException {

    public ConfigurationValidationException(String message) {
        super(message);
    }
}
