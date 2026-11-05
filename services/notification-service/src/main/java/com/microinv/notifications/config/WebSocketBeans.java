package com.microinv.notifications.config;

import com.microinv.notifications.ws.StompAuthChannelInterceptor;
import com.microinv.notifications.ws.WebSocketSessionTracker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** Beans de apoyo del WebSocket (separados de {@link WebSocketConfig} para evitar dependencias circulares). */
@Configuration
public class WebSocketBeans {

    @Bean(name = "wsTaskScheduler")
    public ThreadPoolTaskScheduler wsTaskScheduler() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("ws-scheduler-");
        taskScheduler.setDaemon(true);
        taskScheduler.setRemoveOnCancelPolicy(true);
        return taskScheduler;
    }

    @Bean
    public WebSocketSessionTracker webSocketSessionTracker(AppProperties properties,
                                                           @Qualifier("wsTaskScheduler") TaskScheduler scheduler) {
        return new WebSocketSessionTracker(scheduler, properties.getWebsocket().getConnectTimeout());
    }

    @Bean
    public StompAuthChannelInterceptor stompAuthChannelInterceptor(JwtDecoder jwtDecoder,
                                                                   WebSocketSessionTracker tracker) {
        return new StompAuthChannelInterceptor(jwtDecoder, tracker);
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        // Límite también en el contenedor: un mensaje de texto/binario mayor de 8 KB cierra la conexión (1009).
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(WebSocketConfig.MAX_MESSAGE_SIZE_BYTES);
        container.setMaxBinaryMessageBufferSize(WebSocketConfig.MAX_MESSAGE_SIZE_BYTES);
        return container;
    }
}
