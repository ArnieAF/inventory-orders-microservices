package com.microinv.inventory.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Valida la configuracion de seguridad ANTES de crear ningun bean (se registra en META-INF/spring.factories).
 * Sin esto, otro fallo de arranque (p. ej. BD sin configurar) taparia el de JWT_SECRET/INTERNAL_API_KEY.
 * Se ejecuta despues de cargar application.yml y las variables de entorno. Nunca incluye los valores.
 */
public class SecurityConfigurationPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            SecurityProperties.validate(
                    environment.getProperty("app.security.jwt-secret"),
                    environment.getProperty("app.security.jwt-issuer"),
                    environment.getProperty("app.security.internal-api-key"));
        } catch (IllegalArgumentException e) {
            throw new StartupConfigurationException(e.getMessage());
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // despues de ConfigDataEnvironmentPostProcessor
    }
}
