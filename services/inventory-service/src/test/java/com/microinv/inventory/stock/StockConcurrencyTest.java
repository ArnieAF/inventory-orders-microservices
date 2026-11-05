package com.microinv.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Concurrencia real sobre la BD (H2 con FOR UPDATE): nunca stock negativo, nunca sobreventa, sin
 * interbloqueos y con idempotencia incluso con peticiones simultaneas del mismo orderRef.
 */
class StockConcurrencyTest extends AbstractIntegrationTest {

    private static final int TIMEOUT_SECONDS = 60;

    @Autowired
    private StockService stockService;

    private static ReserveRequest.Item item(UUID productId, int quantity) {
        return new ReserveRequest.Item(productId, quantity);
    }

    /** Resultado de cada hilo: respuesta, o la excepcion que lanzo. */
    private record Outcome(ReserveResponse response, Throwable error) {
        boolean ok() {
            return error == null;
        }

        boolean insufficient() {
            return error instanceof ApiException e && e.getCode() == ErrorCode.INSUFFICIENT_STOCK;
        }
    }

    private List<Outcome> runConcurrently(List<Callable<ReserveResponse>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (Callable<ReserveResponse> task : tasks) {
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
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyThreadsReservingTheLastUnitsNeverOversellNorGoNegative() throws Exception {
        int stock = 7;
        int threads = 30;
        UUID p = insertProduct("CC-1", stock, 2);

        List<Callable<ReserveResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID order = UUID.randomUUID();
            tasks.add(() -> stockService.reserve(order, List.of(item(p, 1))));
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        long ok = outcomes.stream().filter(Outcome::ok).count();
        long insufficient = outcomes.stream().filter(Outcome::insufficient).count();
        assertThat(outcomes).filteredOn(o -> !o.ok() && !o.insufficient())
                .as("ningun error inesperado (interbloqueos, bloqueos optimistas, 500...)").isEmpty();
        assertThat(ok).isEqualTo(stock);
        assertThat(insufficient).isEqualTo(threads - stock);
        assertThat(stockOf(p)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where type = 'RESERVE'", Integer.class))
                .isEqualTo(stock);
        assertThat(jdbc.queryForObject("select sum(delta) from stock_movements", Integer.class)).isEqualTo(-stock);
    }

    @Test
    void competingBigReservationsOnlyOneFitsAndStockStaysConsistent() throws Exception {
        UUID p = insertProduct("CC-2", 10, 2);
        List<Callable<ReserveResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID order = UUID.randomUUID();
            tasks.add(() -> stockService.reserve(order, List.of(item(p, 6)))); // solo cabe una de 6 en 10
        }

        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(Outcome::ok).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(Outcome::insufficient).count()).isEqualTo(7);
        assertThat(stockOf(p)).isEqualTo(4);
    }

    @Test
    void oppositeRequestOrdersOverTheSameProductsDoNotDeadlock() throws Exception {
        UUID a = insertProduct("CC-3A", 100, 2);
        UUID b = insertProduct("CC-3B", 100, 2);
        List<Callable<ReserveResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            UUID order = UUID.randomUUID();
            List<ReserveRequest.Item> items = i % 2 == 0 ? List.of(item(a, 1), item(b, 1))
                    : List.of(item(b, 1), item(a, 1));
            tasks.add(() -> stockService.reserve(order, items));
        }

        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(stockOf(a)).isEqualTo(76);
        assertThat(stockOf(b)).isEqualTo(76);
    }

    @Test
    void concurrentMultiProductReservationsAreAllOrNothing() throws Exception {
        // A tiene stock para 5 pedidos, B para 3: de 10 pedidos {A,B} solo caben 3 (los limita B), y ni A ni B
        // pueden quedar descontados por pedidos que fallaron.
        UUID a = insertProduct("CC-4A", 5, 0);
        UUID b = insertProduct("CC-4B", 3, 0);
        List<Callable<ReserveResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID order = UUID.randomUUID();
            tasks.add(() -> stockService.reserve(order, List.of(item(a, 1), item(b, 1))));
        }

        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(o -> !o.ok() && !o.insufficient()).isEmpty();
        assertThat(outcomes.stream().filter(Outcome::ok).count()).isEqualTo(3);
        assertThat(stockOf(a)).isEqualTo(2);
        assertThat(stockOf(b)).isZero();
    }

    @Test
    void sameOrderRefReservedConcurrentlyDeductsOnlyOnceAndAllGetTheSameResult() throws Exception {
        UUID p = insertProduct("CC-5", 10, 2);
        UUID order = UUID.randomUUID();
        List<Callable<ReserveResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(() -> stockService.reserve(order, List.of(item(p, 3))));
        }

        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(outcomes.stream().map(Outcome::response).distinct()).hasSize(1);
        assertThat(stockOf(p)).isEqualTo(7);
        assertThat(countMovements(order, MovementType.RESERVE)).isEqualTo(1);
    }

    @Test
    void sameOrderRefReleasedConcurrentlyRestoresOnlyOnce() throws Exception {
        UUID p = insertProduct("CC-6", 10, 2);
        UUID order = UUID.randomUUID();
        stockService.reserve(order, List.of(item(p, 4)));

        List<Callable<ReserveResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(() -> {
                stockService.release(order);
                return null;
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(stockOf(p)).isEqualTo(10);
        assertThat(countMovements(order, MovementType.RELEASE)).isEqualTo(1);
    }

    @Test
    void reservesAndReleasesInterleavedKeepTheLedgerBalanced() throws Exception {
        UUID p = insertProduct("CC-7", 20, 2);
        List<UUID> orders = new ArrayList<>();
        List<Callable<ReserveResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID order = UUID.randomUUID();
            orders.add(order);
            tasks.add(() -> stockService.reserve(order, List.of(item(p, 1))));
        }
        for (UUID order : orders) {
            tasks.add(() -> {
                stockService.release(order); // puede llegar antes que la reserva: entonces es no-op
                return null;
            });
        }

        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::ok);
        // Invariante: stock final = stock inicial + suma de todos los deltas registrados, y nunca negativo.
        int ledger = jdbc.queryForObject("select coalesce(sum(delta), 0) from stock_movements where product_id = ?",
                Integer.class, p);
        assertThat(stockOf(p)).isEqualTo(20 + ledger).isBetween(10, 20);
    }
}
