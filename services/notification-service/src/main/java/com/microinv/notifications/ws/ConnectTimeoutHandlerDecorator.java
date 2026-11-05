package com.microinv.notifications.ws;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/** Enlaza el ciclo de vida de la conexión WebSocket con el {@link WebSocketSessionTracker}. */
public class ConnectTimeoutHandlerDecorator extends WebSocketHandlerDecorator {

    private final WebSocketSessionTracker tracker;

    public ConnectTimeoutHandlerDecorator(WebSocketHandler delegate, WebSocketSessionTracker tracker) {
        super(delegate);
        this.tracker = tracker;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        tracker.opened(session);
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        tracker.closed(session.getId());
        super.afterConnectionClosed(session, closeStatus);
    }
}
