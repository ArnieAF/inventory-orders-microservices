package com.microinv.notifications.ws;

import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Interceptor del canal de ENTRADA (clientInboundChannel): autenticación y control de destinos (§7). Política por
 * defecto "denegar":
 * <ul>
 *   <li>{@code CONNECT}/{@code STOMP}: exige {@code Authorization: Bearer <jwt>} válido (misma validación que REST);
 *       fija como Principal el {@code sub}. Un segundo CONNECT en la misma sesión se rechaza.</li>
 *   <li>{@code SUBSCRIBE}: solo con sesión autenticada y solo a {@link StompDestinations#BROADCAST_TOPIC} o
 *       {@link StompDestinations#USER_QUEUE_SUBSCRIPTION}, con un tope de
 *       {@link WebSocketSessionTracker#MAX_SUBSCRIPTIONS_PER_SESSION} suscripciones por conexión.</li>
 *   <li>{@code UNSUBSCRIBE}: con sesión autenticada. {@code DISCONNECT} y latidos: siempre.</li>
 *   <li>{@code SEND} y cualquier otro comando (ACK, NACK, BEGIN, COMMIT, ABORT): rechazados. No hay destinos de
 *       aplicación y, sin este bloqueo, un cliente podría publicar directamente en {@code /topic/...}.</li>
 * </ul>
 * Un rechazo lanza {@link StompSecurityException}: Spring responde con un frame ERROR (con el mensaje genérico de
 * la excepción) y cierra la conexión. Nunca se registra el token.
 */
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_SUBSCRIPTION_ID_LENGTH = 64;

    private final JwtDecoder jwtDecoder;
    private final WebSocketSessionTracker tracker;

    public StompAuthChannelInterceptor(JwtDecoder jwtDecoder, WebSocketSessionTracker tracker) {
        this.jwtDecoder = jwtDecoder;
        this.tracker = tracker;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            accessor = StompHeaderAccessor.wrap(message);
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            // Latido de cliente (línea vacía): sin comando STOMP. Cualquier otro mensaje sin comando se rechaza.
            if (accessor.getMessageType() == SimpMessageType.HEARTBEAT) {
                return message;
            }
            throw reject(accessor, StompSecurityException.OPERATION_NOT_ALLOWED);
        }
        switch (command) {
            case CONNECT, STOMP -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscription(accessor);
            case UNSUBSCRIBE -> {
                requireSession(accessor);
                tracker.removeSubscription(accessor.getSessionId(), accessor.getSubscriptionId());
            }
            case DISCONNECT -> {
                // Siempre permitido (también lo genera Spring al cerrarse la sesión).
            }
            default -> throw reject(accessor, StompSecurityException.OPERATION_NOT_ALLOWED);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTHORIZATION);
        if (header == null || header.isBlank()) {
            throw reject(accessor, StompSecurityException.AUTHENTICATION_REQUIRED);
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw reject(accessor, StompSecurityException.INVALID_CREDENTIALS);
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw reject(accessor, StompSecurityException.INVALID_CREDENTIALS);
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (RuntimeException ex) {
            // Solo el tipo de error (nunca el token ni el mensaje, que podría citar partes del token).
            log.debug("STOMP CONNECT with an invalid token: {}", ex.getClass().getSimpleName());
            throw reject(accessor, StompSecurityException.INVALID_CREDENTIALS);
        }
        // El validador ya garantiza que sub es un UUID; se normaliza a la forma canónica en minúsculas.
        Principal principal = new StompPrincipal(UUID.fromString(jwt.getSubject()).toString());
        if (!tracker.authenticate(accessor.getSessionId(), principal)) {
            throw reject(accessor, StompSecurityException.ALREADY_CONNECTED);
        }
        accessor.setUser(principal);
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        Principal principal = requireSession(accessor);
        if (!StompDestinations.isSubscribable(accessor.getDestination())) {
            throw reject(accessor, StompSecurityException.DESTINATION_NOT_ALLOWED);
        }
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null || subscriptionId.isBlank() || subscriptionId.length() > MAX_SUBSCRIPTION_ID_LENGTH) {
            throw reject(accessor, StompSecurityException.OPERATION_NOT_ALLOWED);
        }
        // Tope de suscripciones por conexión: evita que un cliente autenticado acumule miles en el broker.
        if (!tracker.addSubscription(accessor.getSessionId(), subscriptionId)) {
            throw reject(accessor, StompSecurityException.TOO_MANY_SUBSCRIPTIONS);
        }
        // El usuario de la suscripción sale de nuestro registro, no de lo que traiga el frame.
        accessor.setUser(principal);
    }

    private Principal requireSession(StompHeaderAccessor accessor) {
        Principal principal = tracker.principal(accessor.getSessionId());
        if (principal == null) {
            throw reject(accessor, StompSecurityException.AUTHENTICATION_REQUIRED);
        }
        return principal;
    }

    private StompSecurityException reject(StompHeaderAccessor accessor, String publicMessage) {
        log.info("STOMP {} rejected for session {}: {}", accessor.getCommand(), accessor.getSessionId(),
                publicMessage);
        return new StompSecurityException(publicMessage);
    }
}
