package com.microinv.notifications.ws;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Estado por conexión WebSocket: temporizador de CONNECT (§7: si no llega un CONNECT válido a tiempo se cierra la
 * conexión) y el principal autenticado de cada sesión.
 *
 * <p>Una sesión nace en {@link #opened(WebSocketSession)} (handshake aceptado), pasa a autenticada con
 * {@link #authenticate(String, Principal)} cuando llega un CONNECT con JWT válido y desaparece en
 * {@link #closed(String)}.
 */
public class WebSocketSessionTracker {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionTracker.class);

    private static final class Entry {
        private volatile Principal principal;
        private volatile ScheduledFuture<?> timeout;
    }

    private final ConcurrentMap<String, Entry> sessions = new ConcurrentHashMap<>();
    private final TaskScheduler scheduler;
    private final Duration connectTimeout;

    public WebSocketSessionTracker(TaskScheduler scheduler, Duration connectTimeout) {
        this.scheduler = scheduler;
        this.connectTimeout = connectTimeout;
    }

    /** Registra la sesión y arma el temporizador que la cierra si no llega un CONNECT válido a tiempo. */
    public void opened(WebSocketSession session) {
        Entry entry = new Entry();
        sessions.put(session.getId(), entry);
        entry.timeout = scheduler.schedule(() -> expire(session, entry), Instant.now().plus(connectTimeout));
    }

    private void expire(WebSocketSession session, Entry entry) {
        if (entry.principal != null || sessions.get(session.getId()) != entry) {
            return;
        }
        log.info("Closing WebSocket session {}: no valid STOMP CONNECT within {}", session.getId(), connectTimeout);
        try {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("CONNECT timeout"));
        } catch (Exception ex) {
            log.debug("Could not close WebSocket session {}: {}", session.getId(), ex.toString());
        }
    }

    /**
     * Marca la sesión como autenticada y cancela su temporizador.
     *
     * @return {@code false} si la sesión es desconocida o ya estaba autenticada (un segundo CONNECT no puede
     *         cambiar la identidad de la sesión)
     */
    public boolean authenticate(String sessionId, Principal principal) {
        Entry entry = sessionId == null ? null : sessions.get(sessionId);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entry.principal != null) {
                return false;
            }
            entry.principal = principal;
        }
        ScheduledFuture<?> timeout = entry.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
        return true;
    }

    /** Principal autenticado de la sesión, o {@code null} si aún no hizo un CONNECT válido. */
    public Principal principal(String sessionId) {
        Entry entry = sessionId == null ? null : sessions.get(sessionId);
        return entry == null ? null : entry.principal;
    }

    public void closed(String sessionId) {
        Entry entry = sessions.remove(sessionId);
        if (entry != null && entry.timeout != null) {
            entry.timeout.cancel(false);
        }
    }

    /** Número de sesiones abiertas (diagnóstico y pruebas). */
    public int openSessions() {
        return sessions.size();
    }
}
