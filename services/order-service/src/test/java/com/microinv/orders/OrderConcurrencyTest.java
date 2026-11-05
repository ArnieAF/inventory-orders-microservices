package com.microinv.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microinv.orders.client.NotificationRequest;
import com.microinv.orders.domain.OrderEntity;
import com.microinv.orders.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.microinv.orders.domain.OrderStatus;
import com.microinv.orders.error.ApiException;
import com.microinv.orders.error.ErrorCode;
import com.microinv.orders.repository.OrderRepository;
import com.microinv.orders.security.CurrentUser;
import com.microinv.orders.service.OrderStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/** Dos cambios de estado concurrentes sobre el mismo pedido: solo uno puede ganar (bloqueo optimista). */
class OrderConcurrencyTest extends AbstractApiTest {

    @Autowired
    OrderStore store;
    @Autowired
    OrderRepository repository;
    @Autowired
    TransactionTemplate tx;

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(OrderService.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureServiceLogs() {
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void releaseServiceLogs() {
        serviceLogger.detachAppender(logs);
    }

    private List<String> errorLogs() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private int patchStatus(UUID orderId, String status, UUID userId, String username) throws Exception {
        return mvc.perform(authed(patch("/api/orders/" + orderId + "/status"), userId, username)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private <T> List<T> runAtTheSameTime(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyConcurrentConfirmationsOfTheSameOrderHaveExactlyOneWinner() throws Exception {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID user = UUID.randomUUID();
            tasks.add(() -> patchStatus(orderId, "CONFIRMED", user, "user"));
        }

        List<Integer> statuses = runAtTheSameTime(tasks);

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(7);
        assertThat(statusInDb(orderId)).isEqualTo("CONFIRMED");
        assertThat(versionInDb(orderId)).isEqualTo(1L); // una sola escritura
        verify(notifications, times(1)).send(any()); // y una sola notificacion
    }

    // ---- cancelacion: el stock ya esta liberado cuando otro usuario cambia el pedido -----------------------------

    /** Invariante: un pedido cuyo stock se libero NUNCA queda activo sin que se registre el error de conciliacion. */
    private void assertNoReconciliationError() {
        assertThat(errorLogs()).as("no reconciliation ERROR expected").isEmpty();
    }

    @Test
    void twoCancellationsBothPastTheReleaseStepBothSucceedAndEndCancelledWithASingleWrite() throws Exception {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        // Las dos peticiones leen la misma version y solo avanzan cuando AMBAS han llegado a release:
        // fuerza la carrera real sobre la confirmacion del estado.
        CyclicBarrier bothReadTheSameVersion = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothReadTheSameVersion.await(10, TimeUnit.SECONDS);
            return null;
        }).when(inventory).release(any());

        List<Integer> statuses = runAtTheSameTime(List.of(
                () -> patchStatus(orderId, "CANCELLED", UUID.randomUUID(), "u1"),
                () -> patchStatus(orderId, "CANCELLED", UUID.randomUUID(), "u2")));

        // El perdedor relee, ve CANCELLED y devuelve exito idempotente (no un 409 con el stock ya liberado).
        assertThat(statuses).containsExactly(200, 200);
        assertThat(statusInDb(orderId)).isEqualTo("CANCELLED");
        assertThat(versionInDb(orderId)).isEqualTo(1L);
        verify(inventory, times(2)).release(orderId); // release es idempotente: repetirlo es inocuo
        verify(notifications, times(1)).send(any()); // una sola notificacion: la del ganador
        assertNoReconciliationError();
    }

    @Test
    void confirmAndCancelRacingAlwaysEndCancelledBecauseTheReleasedCancellationIsRetried() throws Exception {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        // La cancelacion ya ha leido la version 0 cuando llega a release; solo entonces arranca la confirmacion.
        CyclicBarrier cancelHasPlanned = new CyclicBarrier(2);
        doAnswer(invocation -> {
            cancelHasPlanned.await(10, TimeUnit.SECONDS);
            return null;
        }).when(inventory).release(any());

        List<Integer> statuses = runAtTheSameTime(List.of(
                () -> patchStatus(orderId, "CANCELLED", UUID.randomUUID(), "u1"),
                () -> {
                    cancelHasPlanned.await(10, TimeUnit.SECONDS);
                    return patchStatus(orderId, "CONFIRMED", UUID.randomUUID(), "u2");
                }));

        int cancel = statuses.get(0);
        int confirm = statuses.get(1);
        // Con el stock liberado el pedido NUNCA puede acabar CONFIRMED: la cancelacion siempre se completa.
        assertThat(cancel).isEqualTo(200);
        assertThat(confirm).isIn(200, 409);
        assertThat(statusInDb(orderId)).isEqualTo("CANCELLED");
        // confirmar primero = 2 escrituras (CONFIRMED y luego CANCELLED); cancelar primero = 1 (CONFIRMED se rechaza)
        assertThat(versionInDb(orderId)).isEqualTo(confirm == 200 ? 2L : 1L);
        verify(notifications, times(confirm == 200 ? 2 : 1)).send(any());
        assertNoReconciliationError();
    }

    @Test
    void ifTheOrderIsConfirmedWhileTheStockIsBeingReleasedTheCancellationStillCompletes() throws Exception {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        UUID other = UUID.randomUUID();
        // Determinista: otro usuario confirma EXACTAMENTE entre la planificacion y el commit de la cancelacion.
        doAnswer(invocation -> {
            store.applyTransition(orderId, 0L, OrderStatus.CONFIRMED, new CurrentUser(other, "bob"));
            return null;
        }).when(inventory).release(any());

        mvc.perform(authed(patch("/api/orders/" + orderId + "/status"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(statusInDb(orderId)).isEqualTo("CANCELLED");
        assertThat(versionInDb(orderId)).isEqualTo(2L);
        verify(inventory, times(1)).release(orderId); // el reintento NO repite el release
        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).send(sent.capture());
        assertThat(sent.getAllValues().get(1).message()).contains("CONFIRMED").contains("CANCELLED");
        assertThat(sent.getAllValues().get(1).actorUserId()).isEqualTo(USER_ID);
        assertNoReconciliationError();
    }

    @Test
    void ifAnotherCancellationWinsWhileTheStockIsBeingReleasedTheResultIsIdempotentSuccess() throws Exception {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        doAnswer(invocation -> {
            store.applyTransition(orderId, 0L, OrderStatus.CANCELLED, new CurrentUser(UUID.randomUUID(), "bob"));
            return null;
        }).when(inventory).release(any());

        mvc.perform(authed(patch("/api/orders/" + orderId + "/status"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.id").value(orderId.toString()));

        assertThat(versionInDb(orderId)).isEqualTo(1L);
        verify(notifications, times(1)).send(any()); // solo la del ganador
        assertNoReconciliationError();
    }

    @Test
    void ifTheOrderShipsWhileTheStockIsBeingReleasedTheReconciliationErrorIsLoggedAndThe409IsKept() throws Exception {
        UUID orderId = seedOrder(OrderStatus.CONFIRMED);
        doAnswer(invocation -> {
            store.applyTransition(orderId, 0L, OrderStatus.SHIPPED, new CurrentUser(UUID.randomUUID(), "bob"));
            return null;
        }).when(inventory).release(any());

        mvc.perform(authed(patch("/api/orders/" + orderId + "/status"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        // el pedido sigue activo (SHIPPED) con el stock ya liberado: debe quedar el ERROR para conciliar
        assertThat(statusInDb(orderId)).isEqualTo("SHIPPED");
        assertThat(errorLogs()).singleElement().satisfies(message -> assertThat(message)
                .contains(orderId.toString()).contains("stock released but order still active")
                .contains("SHIPPED"));
        verify(inventory, times(1)).release(orderId);
        verify(notifications, times(1)).send(any()); // solo el SHIPPED
    }

    @Test
    void confirmedOrderCancelledWhileSomeoneShipsItEndsEitherCancelledOrShippedWithTheErrorLogged() throws Exception {
        UUID orderId = seedOrder(OrderStatus.CONFIRMED);
        CyclicBarrier cancelHasPlanned = new CyclicBarrier(2);
        doAnswer(invocation -> {
            cancelHasPlanned.await(10, TimeUnit.SECONDS);
            return null;
        }).when(inventory).release(any());

        List<Integer> statuses = runAtTheSameTime(List.of(
                () -> patchStatus(orderId, "CANCELLED", UUID.randomUUID(), "u1"),
                () -> {
                    cancelHasPlanned.await(10, TimeUnit.SECONDS);
                    return patchStatus(orderId, "SHIPPED", UUID.randomUUID(), "u2");
                }));

        // Invariante: o acaba CANCELLED, o el otro la hizo no cancelable y queda el ERROR registrado.
        // Nunca un pedido activo con el stock liberado SIN registro.
        String finalStatus = statusInDb(orderId);
        if (finalStatus.equals("CANCELLED")) {
            assertThat(statuses.get(0)).isEqualTo(200);
            assertThat(statuses.get(1)).isEqualTo(409); // SHIPPED ya no es posible desde CANCELLED
            assertNoReconciliationError();
        } else {
            assertThat(finalStatus).isEqualTo("SHIPPED");
            assertThat(statuses).containsExactly(409, 200);
            assertThat(errorLogs()).singleElement().satisfies(message -> assertThat(message)
                    .contains(orderId.toString()).contains("stock released but order still active"));
        }
    }

    @Test
    void staleVersionIsRejectedByTheStoreWith409() {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        CurrentUser actor = new CurrentUser(UUID.randomUUID(), "carol");

        OrderStore.TransitionPlan planA = store.planTransition(orderId, OrderStatus.CONFIRMED);
        OrderStore.TransitionPlan planB = store.planTransition(orderId, OrderStatus.CANCELLED);
        assertThat(planA.expectedVersion()).isEqualTo(planB.expectedVersion());

        store.applyTransition(orderId, planA.expectedVersion(), OrderStatus.CONFIRMED, actor); // gana A

        assertThatThrownBy(() -> store.applyTransition(orderId, planB.expectedVersion(), OrderStatus.CANCELLED, actor))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
                });
        assertThat(statusInDb(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void versionAnnotationIsEnforcedByTheDatabaseLayer() {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        // A carga el pedido (version 0) y queda desconectado.
        OrderEntity detachedA = tx.execute(status -> {
            OrderEntity order = repository.findWithItemsById(orderId).orElseThrow();
            order.getItems().size();
            return order;
        });
        // B cambia el pedido y confirma (version 1).
        store.applyTransition(orderId, 0L, OrderStatus.CONFIRMED, new CurrentUser(UUID.randomUUID(), "bob"));

        // A intenta escribir con la version 0 obsoleta: Hibernate debe rechazarlo, no pisar el cambio de B.
        detachedA.changeStatus(OrderStatus.CANCELLED, java.time.Instant.now());
        assertThatThrownBy(() -> repository.saveAndFlush(detachedA))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(statusInDb(orderId)).isEqualTo("CONFIRMED");
    }
}
