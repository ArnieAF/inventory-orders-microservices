package com.microinv.inventory.notification;

import com.microinv.inventory.config.NotificationProperties;
import com.microinv.inventory.stock.LowStockEvent;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envia el aviso {@code LOW_STOCK} DESPUES del commit de la transaccion que cruzo el umbral
 * ({@code AFTER_COMMIT}: si la transaccion se revierte, el evento se descarta y no sale nada).
 * <p>
 * Best-effort: el envio va a un hilo aparte (cola acotada) para que la latencia o caida de
 * notification-service no retrase la respuesta de una reserva (order-service tiene 5 s de lectura);
 * cualquier fallo se registra y se ignora. Con {@code app.notifications.async=false} (pruebas) el envio
 * es sincrono y determinista.
 */
@Component
public class LowStockNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(LowStockNotificationListener.class);
    static final int QUEUE_CAPACITY = 200;
    static final int MAX_THREADS = 2;

    private final NotificationClient client;
    private final Executor executor;
    private final ThreadPoolExecutor pool; // solo si es asincrono (para cerrarlo)

    public LowStockNotificationListener(NotificationClient client, NotificationProperties properties) {
        this.client = client;
        if (properties.async()) {
            ThreadPoolExecutor tpe = new ThreadPoolExecutor(1, MAX_THREADS, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                Thread t = new Thread(runnable, "low-stock-notifier");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
            this.pool = tpe;
            this.executor = tpe;
        } else {
            this.pool = null;
            this.executor = Runnable::run;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLowStock(LowStockEvent event) {
        NotificationRequest request = toRequest(event);
        try {
            executor.execute(() -> deliver(request));
        } catch (RejectedExecutionException e) {
            log.warn("Notification queue is full; dropping LOW_STOCK notification for productId={}",
                    event.productId());
        } catch (RuntimeException e) {
            log.warn("Could not dispatch LOW_STOCK notification for productId={} (cause={})",
                    event.productId(), e.getClass().getSimpleName());
        }
    }

    private void deliver(NotificationRequest request) {
        try {
            client.send(request);
        } catch (RuntimeException e) {
            log.warn("Notification client failed for productId={} (cause={})", request.entityId(),
                    e.getClass().getSimpleName());
        }
    }

    static NotificationRequest toRequest(LowStockEvent event) {
        String title = "Stock bajo";
        String message = "El producto \"" + event.name() + "\" (" + event.sku() + ") tiene " + event.stock()
                + (event.stock() == 1 ? " unidad" : " unidades") + " (umbral: " + event.threshold() + ")";
        return new NotificationRequest(
                NotificationRequest.TYPE_LOW_STOCK,
                truncate(title, NotificationRequest.MAX_TITLE),
                truncate(message, NotificationRequest.MAX_MESSAGE),
                null,
                event.actorUserId(),
                NotificationRequest.ENTITY_PRODUCT,
                event.productId());
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
