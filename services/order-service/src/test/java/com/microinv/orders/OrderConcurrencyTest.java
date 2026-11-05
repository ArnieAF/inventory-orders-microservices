package com.microinv.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.microinv.orders.domain.OrderEntity;
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

    @Test
    void twoCancellationsBothPastTheReleaseStepStillHaveOneWinner() throws Exception {
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

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(statusInDb(orderId)).isEqualTo("CANCELLED");
        assertThat(versionInDb(orderId)).isEqualTo(1L);
        verify(inventory, times(2)).release(orderId); // release es idempotente: repetirlo es inocuo
        verify(notifications, times(1)).send(any());
    }

    @Test
    void confirmAndCancelRacingHaveExactlyOneWinner() throws Exception {
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

        // Gane quien gane, el otro recibe 409: nunca dos ganadores desde el mismo estado de origen.
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(statusInDb(orderId)).isIn("CONFIRMED", "CANCELLED");
        assertThat(versionInDb(orderId)).isEqualTo(1L);
        verify(notifications, times(1)).send(any());
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
