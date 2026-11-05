package com.microinv.notifications.ws;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Valida la cabecera {@code Origin} del handshake contra {@code ALLOWED_ORIGINS} (§7) por COINCIDENCIA EXACTA
 * (los navegadores envían el origen en minúsculas y sin puerto por defecto). Un origen distinto o mal formado se
 * rechaza con 403 sin intentar interpretarlo (la comprobación interna de Spring lanza una excepción 500 con
 * orígenes mal formados, por eso este guardián va ANTES).
 *
 * <p>Sin cabecera {@code Origin} (clientes que no son navegadores) el handshake se admite: el handshake no da
 * ningún acceso por sí mismo (no hay cookies ni credenciales ambientales) y el frame CONNECT exige un JWT válido.
 * La protección contra WebSocket desde páginas de terceros (CSWSH) se basa en que los navegadores siempre envían
 * {@code Origin}.
 */
public class OriginHandshakeGuard implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OriginHandshakeGuard.class);
    private static final int MAX_LOGGED_ORIGIN_LENGTH = 100;

    private final Set<String> allowedOrigins;

    public OriginHandshakeGuard(Collection<String> normalizedAllowedOrigins) {
        this.allowedOrigins = Set.copyOf(normalizedAllowedOrigins);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null || allowedOrigins.contains(origin)) {
            return true;
        }
        log.warn("WebSocket handshake rejected: origin not allowed ({})", sanitize(origin));
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
        // nada que hacer
    }

    /** Para el log: acota la longitud y sustituye caracteres de control (evita inyección en el log). */
    static String sanitize(String value) {
        String cut = value.length() > MAX_LOGGED_ORIGIN_LENGTH ? value.substring(0, MAX_LOGGED_ORIGIN_LENGTH) : value;
        return cut.replaceAll("[\\p{Cntrl}]", "?");
    }
}
