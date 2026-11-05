package com.microinv.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.inventory.error.ApiException;
import com.microinv.inventory.error.ErrorCode;
import com.microinv.inventory.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cabecera por orderRef (tabla stock_reservations): una compensacion (release) que llega antes que una reserva
 * lenta no puede dejar stock descontado sin pedido, y dos reservas simultaneas del mismo orderRef se serializan
 * aunque toquen productos distintos.
 */
class ReservationHeaderRaceTest extends AbstractIntegrationTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static ReserveRequest.Item item(UUID productId, int quantity) {
        return new ReserveRequest.Item(productId, quantity);
    }

    private String reservationStatus(UUID orderRef) {
        List<String> rows = jdbc.queryForList("select status from stock_reservations where order_ref = ?",
                String.class, orderRef);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------- release antes que reserve: lapida ----------

    @Test
    void releaseOfAnUnknownOrderRefLeavesATombstoneAndALaterReserveIsRejected() {
        UUID p = insertProduct("RACE-1", 10, 2);
        UUID order = UUID.randomUUID();

        stockService.release(order); // la compensacion llega antes que la reserva

        assertThat(reservationStatus(order)).isEqualTo("RELEASED");
        assertThatThrownBy(() -> stockService.reserve(order, List.of(item(p, 4))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        assertThat(stockOf(p)).isEqualTo(10); // la reserva tardia NO descuenta nada
        assertThat(countAllMovements()).isZero();
        assertThat(reservationStatus(order)).isEqualTo("RELEASED"); // la lapida sobrevive al rechazo
    }

    @Test
    void releasingTwiceAfterATombstoneIsStillANoOp() {
        UUID order = UUID.randomUUID();
        stockService.release(order);
        stockService.release(order);
        assertThat(jdbc.queryForObject("select count(*) from stock_reservations where order_ref = ?",
                Integer.class, order)).isEqualTo(1);
    }

    @Test
    void headerTracksTheLifecycleOfAReservation() {
        UUID p = insertProduct("RACE-2", 10, 2);
        UUID order = UUID.randomUUID();

        stockService.reserve(order, List.of(item(p, 3)));
        assertThat(reservationStatus(order)).isEqualTo("RESERVED");

        stockService.release(order);
        assertThat(reservationStatus(order)).isEqualTo("RELEASED");
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    void aFailedReservationLeavesNoHeaderBehind() {
        UUID p = insertProduct("RACE-3", 1, 0);
        UUID order = UUID.randomUUID();

        assertThatThrownBy(() -> stockService.reserve(order, List.of(item(p, 5)))).isInstanceOf(ApiException.class);

        assertThat(reservationStatus(order)).isNull(); // se revirtio con la transaccion: se puede reintentar
        stockService.release(order); // y un release posterior (compensacion) deja la lapida
        assertThat(reservationStatus(order)).isEqualTo("RELEASED");
    }

    // ---------- carreras ----------

    /** Resultado de un hilo: respuesta o excepcion. */
    private record Outcome(Object value, Throwable error) {
        boolean ok() {
            return error == null;
        }

        boolean hasCode(ErrorCode code) {
            return error instanceof ApiException e && e.getCode() == code;
        }
    }

    private List<Outcome> runTogether(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome(task.call(), null);
                    } catch (Throwable t) {
                        return new Outcome(null, t);
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoConcurrentReservesOfTheSameOrderRefWithDisjointProductsNeverDeductTwice() throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID a = insertProduct("RACE-4A-" + round, 10, 0);
            UUID b = insertProduct("RACE-4B-" + round, 10, 0);
            UUID order = UUID.randomUUID();

            List<Outcome> outcomes = runTogether(List.of(
                    () -> stockService.reserve(order, List.of(item(a, 3))),
                    () -> stockService.reserve(order, List.of(item(b, 4)))));

            // Se serializan: una gana y la otra ve la reserva de la primera con otros items (400).
            assertThat(outcomes).as("ronda %d", round).filteredOn(Outcome::ok).hasSize(1);
            assertThat(outcomes).filteredOn(o -> !o.ok()).hasSize(1)
                    .allMatch(o -> o.hasCode(ErrorCode.VALIDATION_ERROR));
            int deducted = (10 - stockOf(a)) + (10 - stockOf(b));
            assertThat(deducted).as("unidades descontadas en total, ronda %d", round).isIn(3, 4);
            assertThat(countMovements(order, MovementType.RESERVE)).isEqualTo(1);
            assertThat(reservationStatus(order)).isEqualTo("RESERVED");
        }
    }

    @Test
    void aReleaseRacingAReserveNeverLeavesStockDeductedWithoutAnOrder() throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID p = insertProduct("RACE-5-" + round, 10, 0);
            UUID order = UUID.randomUUID();

            List<Outcome> outcomes = runTogether(List.of(
                    () -> stockService.reserve(order, List.of(item(p, 4))),
                    () -> {
                        stockService.release(order);
                        return null;
                    }));

            // Pase lo que pase en cada orden de llegada, el estado final es coherente: el pedido esta liberado
            // (no hay stock descontado para el) y la reserva tardia, si perdio la carrera, recibio 409.
            assertThat(outcomes.get(1).ok()).as("release siempre responde 204").isTrue();
            assertThat(outcomes.get(0).ok() || outcomes.get(0).hasCode(ErrorCode.INVALID_STATUS_TRANSITION))
                    .as("ronda %d: reserve ok o 409", round).isTrue();
            assertThat(stockOf(p)).as("ronda %d", round).isEqualTo(10);
            assertThat(reservationStatus(order)).isEqualTo("RELEASED");
        }
    }

    @Test
    void aReleaseCommittedWhileASlowReserveWaitsForAProductLockWinsOverTheLateReserve() throws Exception {
        // Reproduce la carrera original con barreras: la reserva queda esperando el bloqueo de la fila del
        // producto (otra transaccion lo tiene), el llamante "agota su lectura" y compensa con release, y solo
        // despues se libera el bloqueo. Nunca debe quedar stock descontado para ese orderRef.
        UUID p = insertProduct("RACE-6", 10, 0);
        UUID order = UUID.randomUUID();
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch freeLock = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> blocker = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                productRepository.findByIdForUpdate(p);
                lockHeld.countDown();
                try {
                    freeLock.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Outcome> lateReserve = pool.submit(() -> {
                try {
                    return new Outcome(stockService.reserve(order, List.of(item(p, 4))), null);
                } catch (Throwable t) {
                    return new Outcome(null, t);
                }
            });
            Thread.sleep(500); // la reserva ya esta esperando el bloqueo del producto
            Future<Outcome> compensation = pool.submit(() -> {
                try {
                    stockService.release(order);
                    return new Outcome(null, null);
                } catch (Throwable t) {
                    return new Outcome(null, t);
                }
            });
            Thread.sleep(500);
            freeLock.countDown(); // el bloqueo se libera: la reserva tardia obtiene la fila

            blocker.get(30, TimeUnit.SECONDS);
            Outcome reserveOutcome = lateReserve.get(30, TimeUnit.SECONDS);
            Outcome releaseOutcome = compensation.get(30, TimeUnit.SECONDS);

            assertThat(releaseOutcome.ok()).as("release responde 204").isTrue();
            assertThat(reserveOutcome.ok() || reserveOutcome.hasCode(ErrorCode.INVALID_STATUS_TRANSITION)).isTrue();
            assertThat(stockOf(p)).as("sin stock descontado para un pedido compensado").isEqualTo(10);
            assertThat(reservationStatus(order)).isEqualTo("RELEASED");
        } finally {
            freeLock.countDown();
            pool.shutdownNow();
        }
    }
}
