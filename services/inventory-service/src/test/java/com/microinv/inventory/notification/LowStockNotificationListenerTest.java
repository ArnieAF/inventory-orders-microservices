package com.microinv.inventory.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.microinv.inventory.config.NotificationProperties;
import com.microinv.inventory.stock.LowStockEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LowStockNotificationListenerTest {

    private static NotificationProperties props(boolean async) {
        return new NotificationProperties("http://notification-service:8084", Duration.ofSeconds(2),
                Duration.ofSeconds(5), async);
    }

    private static LowStockEvent event(UUID actor) {
        return new LowStockEvent(UUID.randomUUID(), "SKU-1", "Teclado", 4, 5, actor);
    }

    @Test
    void buildsContractBodyForBroadcastLowStock() {
        UUID actor = UUID.randomUUID();
        LowStockEvent e = event(actor);

        NotificationRequest r = LowStockNotificationListener.toRequest(e);

        assertThat(r.type()).isEqualTo("LOW_STOCK");
        assertThat(r.entityType()).isEqualTo("PRODUCT");
        assertThat(r.entityId()).isEqualTo(e.productId());
        assertThat(r.targetUserId()).isNull();
        assertThat(r.actorUserId()).isEqualTo(actor);
        assertThat(r.title()).hasSizeLessThanOrEqualTo(NotificationRequest.MAX_TITLE);
        assertThat(r.message()).contains("Teclado").contains("SKU-1").contains("4");
    }

    @Test
    void messageNeverExceedsContractLimit() {
        LowStockEvent e = new LowStockEvent(UUID.randomUUID(), "S".repeat(40), "N".repeat(5000), 1, 5, null);
        assertThat(LowStockNotificationListener.toRequest(e).message())
                .hasSizeLessThanOrEqualTo(NotificationRequest.MAX_MESSAGE);
    }

    @Test
    void syncModeDeliversAndSwallowsClientFailures() {
        NotificationClient client = mock(NotificationClient.class);
        doThrow(new IllegalStateException("boom")).when(client).send(any());
        LowStockNotificationListener listener = new LowStockNotificationListener(client, props(false));

        assertThatCode(() -> listener.onLowStock(event(null))).doesNotThrowAnyException();

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(client).send(captor.capture());
        assertThat(captor.getValue().actorUserId()).isNull();
    }

    @Test
    void asyncModeDoesNotBlockTheCallerWhileTheServiceIsSlow() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        NotificationClient slow = request -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
        NotificationClient spy = mock(NotificationClient.class);
        LowStockNotificationListener blocking = new LowStockNotificationListener(slow, props(true));
        try {
            long start = System.nanoTime();
            blocking.onLowStock(event(null));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).as("la reserva no debe esperar a notification-service").isLessThan(500);
        } finally {
            release.countDown();
            blocking.shutdown();
        }

        LowStockNotificationListener async = new LowStockNotificationListener(spy, props(true));
        try {
            async.onLowStock(event(null));
            verify(spy, timeout(2000)).send(any());
        } finally {
            async.shutdown();
        }
    }

    @Test
    void asyncModeDropsInsteadOfFailingWhenQueueIsFull() throws Exception {
        int submittedCount = 1000;
        CountDownLatch release = new CountDownLatch(1);
        List<UUID> delivered = Collections.synchronizedList(new ArrayList<>());
        NotificationClient stuck = request -> {
            try {
                release.await(10, TimeUnit.SECONDS); // el servicio de notificaciones "no responde"
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            delivered.add(request.entityId());
        };
        List<UUID> submitted = new ArrayList<>();
        LowStockNotificationListener listener = new LowStockNotificationListener(stuck, props(true));
        try {
            assertThatCode(() -> {
                for (int i = 0; i < submittedCount; i++) {
                    LowStockEvent e = event(null);
                    submitted.add(e.productId());
                    listener.onLowStock(e); // nunca lanza ni bloquea, aunque la cola este llena
                }
            }).doesNotThrowAnyException();
        } finally {
            release.countDown();
            listener.shutdown(); // espera a que se vacie lo aceptado
        }

        // Se entrega lo aceptado (hilos activos + cola) y el resto se DESCARTA.
        int accepted = LowStockNotificationListener.QUEUE_CAPACITY + LowStockNotificationListener.MAX_THREADS;
        assertThat(delivered).hasSizeBetween(LowStockNotificationListener.QUEUE_CAPACITY, accepted);
        assertThat(delivered.size()).as("hubo descartes").isLessThan(submittedCount);
        // Las primeras (las que entraron en el hilo y en la cola) son las que se entregan, sin duplicados.
        assertThat(delivered).containsAll(submitted.subList(0, LowStockNotificationListener.QUEUE_CAPACITY));
        assertThat(delivered).doesNotHaveDuplicates();
        // Las ultimas (rechazadas por cola llena) no se entregaron.
        assertThat(delivered).doesNotContainAnyElementsOf(submitted.subList(accepted, submittedCount));
    }
}
