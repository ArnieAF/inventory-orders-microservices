package com.microinv.orders.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Comprueba la configuracion obligatoria ANTES de crear ningun bean: asi el servicio falla de inmediato y con
 * un mensaje claro, sin llegar a conectarse a la base de datos ni a ejecutar migraciones con una configuracion
 * de seguridad invalida. Se llama a los constructores de {@link AppProperties} (sin pasar por el binder) para que
 * el valor del secreto jamas aparezca en ningun mensaje de error.
 */
public class RequiredConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        new AppProperties.Jwt(
                environment.getProperty("app.jwt.secret"),
                environment.getProperty("app.jwt.issuer", "micro-inventory"));
        new AppProperties.Internal(environment.getProperty("app.internal.api-key"));
    }

    /** Despues de cargar application.yml y las variables de entorno. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
