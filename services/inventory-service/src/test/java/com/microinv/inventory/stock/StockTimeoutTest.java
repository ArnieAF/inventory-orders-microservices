package com.microinv.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microinv.inventory.support.AbstractIntegrationTest;
import com.microinv.inventory.support.TestSecrets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Timeouts de reserve/release (contrato 6.2): si la fila de un producto esta bloqueada por otra transaccion,
 * inventario debe fallar con 503 + Retry-After ANTES de los 5 s de lectura de order-service (4 s), sin dejar
 * nada aplicado. Los tests usan H2 con el equivalente del lock_timeout de PostgreSQL (ver application-test.yml).
 */
class StockTimeoutTest extends AbstractIntegrationTest {

    /** Lectura de order-service (contrato 3); inventario debe responder antes. */
    private static final long CALLER_READ_TIMEOUT_MILLIS = 5000;

    @Autowired
    private StockService stockService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private CountDownLatch freeLock;

    @AfterEach
    void cleanup() {
        if (freeLock != null) {
            freeLock.countDown();
        }
        pool.shutdownNow();
    }

    /** Otra transaccion toma SELECT ... FOR UPDATE sobre el producto y no la suelta hasta {@code freeLock}. */
    private Future<?> holdProductLock(UUID productId) throws InterruptedException {
        freeLock = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        Future<?> blocker = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            productRepository.findByIdForUpdate(productId);
            held.countDown();
            try {
                freeLock.await(40, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        return blocker;
    }

    private MockHttpServletRequestBuilder internal(String path, String body) {
        return post(path).header("X-Internal-Key", TestSecrets.INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String reserveBody(UUID order, UUID product, int quantity) {
        return "{\"orderRef\":\"" + order + "\",\"items\":[{\"productId\":\"" + product + "\",\"quantity\":"
                + quantity + "}]}";
    }

    private static void assertServiceUnavailable(ResultActions result) throws Exception {
        result.andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.path").isString());
    }

    private int reservationRows(UUID order) {
        return jdbc.queryForObject("select count(*) from stock_reservations where order_ref = ?", Integer.class,
                order);
    }

    @Test
    void reserveWaitingOnALockedProductFailsWith503BeforeTheCallerReadTimeout() throws Exception {
        UUID p = insertProduct("TMO-1", 10, 2);
        UUID order = UUID.randomUUID();
        Future<?> blocker = holdProductLock(p);

        long start = System.nanoTime();
        assertServiceUnavailable(mvc.perform(internal("/internal/stock/reserve", reserveBody(order, p, 3))));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).as("espero el timeout (no fallo al instante)").isGreaterThan(2500);
        assertThat(elapsedMillis).as("antes de que order-service agote su lectura de 5 s")
                .isLessThan(CALLER_READ_TIMEOUT_MILLIS);

        freeLock.countDown();
        blocker.get(10, TimeUnit.SECONDS);
        // Nada quedo aplicado: ni stock, ni movimientos, ni cabecera (se revirtio todo).
        assertThat(stockOf(p)).isEqualTo(10);
        assertThat(countAllMovements()).isZero();
        assertThat(reservationRows(order)).isZero();
        // El mismo orderRef se puede reintentar despues (idempotente) y funciona.
        mvc.perform(internal("/internal/stock/reserve", reserveBody(order, p, 3))).andExpect(status().isOk());
        assertThat(stockOf(p)).isEqualTo(7);
    }

    @Test
    void releaseWaitingOnALockedProductFailsWith503AndKeepsTheReservation() throws Exception {
        UUID p = insertProduct("TMO-2", 10, 2);
        UUID order = UUID.randomUUID();
        stockService.reserve(order, java.util.List.of(new ReserveRequest.Item(p, 4))); // stock 6
        Future<?> blocker = holdProductLock(p);

        long start = System.nanoTime();
        assertServiceUnavailable(mvc.perform(internal("/internal/stock/release", "{\"orderRef\":\"" + order + "\"}")));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMillis).isGreaterThan(2500).isLessThan(CALLER_READ_TIMEOUT_MILLIS);

        freeLock.countDown();
        blocker.get(10, TimeUnit.SECONDS);
        assertThat(stockOf(p)).isEqualTo(6); // sigue reservado: el release no se aplico
        assertThat(jdbc.queryForObject("select status from stock_reservations where order_ref = ?", String.class,
                order)).isEqualTo("RESERVED");
        // Reintento: ahora si.
        mvc.perform(internal("/internal/stock/release", "{\"orderRef\":\"" + order + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    void aCompensationArrivingWhileASlowReserveWaitsEndsWithATombstoneAndNoDeductedStock() throws Exception {
        // Escenario original del hallazgo: reserve(O) espera >4 s el bloqueo de la fila; order-service agota su
        // lectura y compensa con release(O). Con el timeout de 4 s la reserva falla ANTES (503) y la
        // compensacion deja la lapida: la reserva tardia nunca llega a confirmar.
        UUID p = insertProduct("TMO-3", 10, 2);
        UUID order = UUID.randomUUID();
        Future<?> blocker = holdProductLock(p);

        Future<ResultActions> reserve = pool.submit(() ->
                mvc.perform(internal("/internal/stock/reserve", reserveBody(order, p, 5))));
        Thread.sleep(1000); // la reserva ya tiene su cabecera sin confirmar y espera el bloqueo del producto
        Future<ResultActions> compensation = pool.submit(() ->
                mvc.perform(internal("/internal/stock/release", "{\"orderRef\":\"" + order + "\"}")));

        assertServiceUnavailable(reserve.get(15, TimeUnit.SECONDS));              // reserve: 503 a los ~4 s
        // La compensacion concurrente: en PostgreSQL el SELECT ... FOR UPDATE no ve la cabecera sin confirmar,
        // espera en el INSERT y, al revertirse la reserva, deja la lapida (204). En H2 ese SELECT espera la fila
        // sin confirmar y falla (503) cuando desaparece. En ambos casos la operacion no deja nada a medias y el
        // reintento (order-service compensa "a ciegas" y reintentable) debe dejar la lapida.
        int firstReleaseStatus = compensation.get(15, TimeUnit.SECONDS).andReturn().getResponse().getStatus();
        assertThat(firstReleaseStatus).isIn(204, 503);
        freeLock.countDown();
        blocker.get(10, TimeUnit.SECONDS);
        mvc.perform(internal("/internal/stock/release", "{\"orderRef\":\"" + order + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(stockOf(p)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select status from stock_reservations where order_ref = ?", String.class,
                order)).isEqualTo("RELEASED");
        // La reserva tardia (reintento del mismo orderRef) se rechaza: 409 y sin descontar nada.
        mvc.perform(internal("/internal/stock/reserve", reserveBody(order, p, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        assertThat(stockOf(p)).isEqualTo(10);
        assertThat(countAllMovements()).isZero();
    }
}
