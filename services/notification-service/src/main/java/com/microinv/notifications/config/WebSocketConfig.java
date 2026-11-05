package com.microinv.notifications.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.notifications.ws.ConnectTimeoutHandlerDecorator;
import com.microinv.notifications.ws.OriginHandshakeGuard;
import com.microinv.notifications.ws.SafeStompErrorHandler;
import com.microinv.notifications.ws.StompAuthChannelInterceptor;
import com.microinv.notifications.ws.WebSocketSessionTracker;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.DefaultContentTypeResolver;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * WebSocket STOMP nativo (sin SockJS) en {@code /ws}, broker simple en memoria (§7).
 *
 * <ul>
 *   <li>Handshake: se valida {@code Origin} contra {@code ALLOWED_ORIGINS} (lista explícita, sin {@code *}).</li>
 *   <li>Autenticación en el frame CONNECT y control de destinos: {@link StompAuthChannelInterceptor}.</li>
 *   <li>Si no llega un CONNECT válido en {@code app.websocket.connect-timeout} (10 s) se cierra la conexión.</li>
 *   <li>Heartbeat 10 s/10 s y tamaño máximo de mensaje entrante de 8 KB.</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final int MAX_MESSAGE_SIZE_BYTES = 8 * 1024;
    private static final long HEARTBEAT_MILLIS = 10_000L;

    private final AppProperties properties;
    private final StompAuthChannelInterceptor authInterceptor;
    private final WebSocketSessionTracker sessionTracker;
    private final TaskScheduler scheduler;
    private final ObjectMapper objectMapper;

    public WebSocketConfig(AppProperties properties, StompAuthChannelInterceptor authInterceptor,
                           WebSocketSessionTracker sessionTracker,
                           @Qualifier("wsTaskScheduler") TaskScheduler scheduler, ObjectMapper objectMapper) {
        this.properties = properties;
        this.authInterceptor = authInterceptor;
        this.sessionTracker = sessionTracker;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Sin .withSockJS(): WebSocket nativo. Origin: nuestro guardián (coincidencia exacta, 403) va primero;
        // la lista también se pasa a la comprobación interna de Spring, que corre después.
        List<String> origins = properties.getWebsocket().normalizedAllowedOrigins();
        registry.addEndpoint("/ws")
                .addInterceptors(new OriginHandshakeGuard(origins))
                .setAllowedOrigins(origins.toArray(String[]::new));
        registry.setErrorHandler(new SafeStompErrorHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Broker simple en memoria. Sin prefijos de aplicación: no hay destinos entrantes.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(scheduler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MAX_MESSAGE_SIZE_BYTES)
                .setSendBufferSizeLimit(64 * 1024)
                .setSendTimeLimit(10_000)
                .addDecoratorFactory(handler -> new ConnectTimeoutHandlerDecorator(handler, sessionTracker));
    }

    @Override
    public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
        // Mismo ObjectMapper que REST: Instant como cadena ISO-8601 y nulos presentes.
        DefaultContentTypeResolver resolver = new DefaultContentTypeResolver();
        resolver.setDefaultMimeType(MimeTypeUtils.APPLICATION_JSON);
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        converter.setContentTypeResolver(resolver);
        messageConverters.add(converter);
        return false;
    }
}
