package com.microinv.notifications.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;

/**
 * Valida la configuración imprescindible lo antes posible (con el entorno ya preparado y ANTES de crear el
 * contexto), para que un {@code JWT_SECRET} o {@code INTERNAL_API_KEY} ausente o inválido sea el primer y único
 * error, y no quede oculto detrás de otro fallo (por ejemplo, de la conexión a la base de datos).
 * Se registra en {@code META-INF/spring.factories}.
 */
public class StartupValidationListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        AppProperties properties = Binder.get(event.getEnvironment())
                .bind("app", Bindable.of(AppProperties.class))
                .orElseGet(AppProperties::new);
        properties.validate();
    }
}
