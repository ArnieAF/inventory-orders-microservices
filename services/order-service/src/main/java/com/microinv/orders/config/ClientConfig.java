package com.microinv.orders.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.orders.client.InventoryClient;
import com.microinv.orders.client.NotificationClient;
import com.microinv.orders.client.RestInventoryClient;
import com.microinv.orders.client.RestNotificationClient;
import com.microinv.orders.service.NotificationDispatcher;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Clientes hacia servicios internos (timeouts conexion 2 s / lectura 5 s) y envio de notificaciones. */
@Configuration
public class ClientConfig {

    @Bean
    InventoryClient inventoryClient(AppProperties props, ObjectMapper objectMapper) {
        AppProperties.Inventory inventory = props.inventory();
        return new RestInventoryClient(inventory.baseUrl(), props.internal().apiKey(),
                inventory.connectTimeoutMs(), inventory.readTimeoutMs(), objectMapper);
    }

    @Bean
    NotificationClient notificationClient(AppProperties props) {
        AppProperties.Notifications notifications = props.notifications();
        return new RestNotificationClient(notifications.baseUrl(), props.internal().apiKey(),
                notifications.connectTimeoutMs(), notifications.readTimeoutMs());
    }

    /**
     * Las notificaciones se envian en un hilo aparte para que un notification-service lento no retrase la
     * respuesta al usuario. Con {@code app.notifications.async=false} (pruebas) se envian en el mismo hilo.
     */
    @Bean
    Executor notificationExecutor(AppProperties props) {
        if (!props.notifications().async()) {
            return Runnable::run;
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("notify-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200); // si se llena, se rechaza y se registra (best-effort)
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor; // el contenedor lo inicializa (InitializingBean) y lo cierra al parar (DisposableBean)
    }

    @Bean
    NotificationDispatcher notificationDispatcher(NotificationClient client, Executor notificationExecutor) {
        return new NotificationDispatcher(client, notificationExecutor);
    }
}
