package com.microinv.orders.service;

import com.microinv.orders.client.NotificationClient;
import com.microinv.orders.client.NotificationRequest;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envio best-effort de notificaciones DESPUES del commit ({@code AFTER_COMMIT}): si la transaccion se revierte
 * no se envia nada, y si el envio falla solo se registra (sin datos sensibles) y se ignora. Jamas lanza
 * excepciones: un fallo aqui no puede romper ni compensar la operacion principal ya confirmada.
 */
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationClient client;
    private final Executor executor;

    public NotificationDispatcher(NotificationClient client, Executor executor) {
        this.client = client;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        dispatch(OrderNotifications.newOrder(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        dispatch(OrderNotifications.statusChanged(event));
    }

    private void dispatch(NotificationRequest request) {
        try {
            executor.execute(() -> send(request));
        } catch (Exception e) {
            log.warn("Notification {} for order {} was not queued: {}", request.type(), request.entityId(),
                    e.getClass().getSimpleName());
        }
    }

    private void send(NotificationRequest request) {
        try {
            client.send(request);
        } catch (Exception e) {
            // Solo tipo, id de pedido y clase del fallo: ni cuerpo, ni cabeceras, ni datos del cliente.
            log.warn("Notification {} for order {} failed: {}", request.type(), request.entityId(),
                    e.getClass().getSimpleName());
        }
    }
}
