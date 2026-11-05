package com.microinv.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import com.microinv.inventory.error.ApiException;
import com.microinv.inventory.error.ApiException.StockShortage;
import com.microinv.inventory.error.ErrorCode;
import com.microinv.inventory.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;

class StockServiceTest extends AbstractIntegrationTest {

    @Autowired
    private StockService stockService;

    private static ReserveRequest.Item item(UUID productId, Integer quantity) {
        return new ReserveRequest.Item(productId, quantity);
    }

    private static void assertApiError(Throwable t, ErrorCode code) {
        assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    // ---------- reserva ----------

    @Test
    void happyPathDecrementsStockRecordsMovementsAndReturnsSnapshot() {
        UUID a = insertProduct("RES-A", 10, 2);
        UUID b = insertProduct("RES-B", 5, 2);
        UUID order = UUID.randomUUID();

        ReserveResponse response = stockService.reserve(order, List.of(item(a, 3), item(b, 5)));

        assertThat(stockOf(a)).isEqualTo(7);
        assertThat(stockOf(b)).isZero();
        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).extracting(ReserveResponse.Item::productId)
                .containsExactlyInAnyOrder(a, b)
                .isSortedAccordingTo(StockOrdering.ASCENDING);
        ReserveResponse.Item first = response.items().stream().filter(i -> i.productId().equals(a)).findFirst()
                .orElseThrow();
        assertThat(first.sku()).isEqualTo("RES-A");
        assertThat(first.name()).isEqualTo("Producto RES-A");
        assertThat(first.unitPrice()).isEqualByComparingTo(new BigDecimal("10.50"));
        assertThat(first.unitPrice().scale()).isEqualTo(2);
        assertThat(first.quantity()).isEqualTo(3);
        assertThat(countMovements(order, MovementType.RESERVE)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select sum(delta) from stock_movements where order_ref = ?", Integer.class,
                order)).isEqualTo(-8);
    }

    @Test
    void reservingExactlyTheAvailableStockIsAllowed() {
        UUID a = insertProduct("EXACT", 4, 0);
        stockService.reserve(UUID.randomUUID(), List.of(item(a, 4)));
        assertThat(stockOf(a)).isZero();
    }

    @Test
    void insufficientStockIsAllOrNothingAndReportsEveryShortage() {
        UUID ok = insertProduct("AON-OK", 10, 2);
        UUID shortB = insertProduct("AON-B", 2, 1);
        UUID shortC = insertProduct("AON-C", 0, 0);
        UUID order = UUID.randomUUID();

        assertThatThrownBy(() -> stockService.reserve(order, List.of(item(ok, 4), item(shortB, 3), item(shortC, 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
                    assertThat(e.getDetails()).asList().containsExactlyInAnyOrder(
                            new StockShortage(shortB, 3, 2), new StockShortage(shortC, 1, 0));
                });

        // Nada se descuenta, ni siquiera el producto que si tenia stock suficiente.
        assertThat(stockOf(ok)).isEqualTo(10);
        assertThat(stockOf(shortB)).isEqualTo(2);
        assertThat(stockOf(shortC)).isZero();
        assertThat(countAllMovements()).isZero();
    }

    @Test
    void unknownProductIsNotFoundAndNothingIsDeducted() {
        UUID ok = insertProduct("NF-OK", 10, 2);
        assertThatThrownBy(() -> stockService.reserve(UUID.randomUUID(), List.of(item(ok, 1), item(UUID.randomUUID(), 1))))
                .satisfies(t -> assertApiError(t, ErrorCode.NOT_FOUND));
        assertThat(stockOf(ok)).isEqualTo(10);
        assertThat(countAllMovements()).isZero();
    }

    @Test
    void inactiveProductIsRejectedAndNothingIsDeducted() {
        UUID ok = insertProduct("IN-OK", 10, 2);
        UUID inactive = insertProduct("IN-OFF", 10, 2);
        jdbc.update("update products set active = false where id = ?", inactive);

        assertThatThrownBy(() -> stockService.reserve(UUID.randomUUID(), List.of(item(ok, 1), item(inactive, 1))))
                .satisfies(t -> assertApiError(t, ErrorCode.PRODUCT_INACTIVE));
        assertThat(stockOf(ok)).isEqualTo(10);
        assertThat(stockOf(inactive)).isEqualTo(10);
        assertThat(countAllMovements()).isZero();
    }

    @Test
    void rejectsInvalidLinesBeforeTouchingAnything() {
        UUID a = insertProduct("VAL-A", 10, 2);
        UUID order = UUID.randomUUID();

        assertThatThrownBy(() -> stockService.reserve(order, List.of(item(a, 0))))
                .satisfies(t -> assertApiError(t, ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> stockService.reserve(order, List.of(item(a, -2))))
                .satisfies(t -> assertApiError(t, ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> stockService.reserve(order, List.of(item(a, 1), item(a, 2))))
                .satisfies(t -> assertApiError(t, ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> stockService.reserve(order, List.of()))
                .satisfies(t -> assertApiError(t, ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> stockService.reserve(order, List.of(item(a, null))))
                .satisfies(t -> assertApiError(t, ErrorCode.VALIDATION_ERROR));
        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(countAllMovements()).isZero();
    }

    @Test
    void reservingTheSameOrderRefTwiceReturnsTheSameResultWithoutDeductingAgain() {
        UUID a = insertProduct("IDEM-A", 10, 2);
        UUID b = insertProduct("IDEM-B", 10, 2);
        UUID order = UUID.randomUUID();
        List<ReserveRequest.Item> items = List.of(item(b, 2), item(a, 3));

        ReserveResponse first = stockService.reserve(order, items);
        ReserveResponse second = stockService.reserve(order, items);
        ReserveResponse third = stockService.reserve(order, List.of(item(a, 3), item(b, 2))); // otro orden de lineas

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(stockOf(a)).isEqualTo(7);
        assertThat(stockOf(b)).isEqualTo(8);
        assertThat(countMovements(order, MovementType.RESERVE)).isEqualTo(2);
    }

    @Test
    void replayStillSucceedsAfterTheProductWasDeactivatedOrRanOut() {
        UUID a = insertProduct("REPLAY", 3, 0);
        UUID order = UUID.randomUUID();
        ReserveResponse first = stockService.reserve(order, List.of(item(a, 3))); // deja stock 0
        jdbc.update("update products set active = false where id = ?", a);

        assertThat(stockService.reserve(order, List.of(item(a, 3)))).isEqualTo(first);
        assertThat(stockOf(a)).isZero();
    }

    @Test
    void reusingAnOrderRefWithDifferentItemsIsRejected() {
        UUID a = insertProduct("DIFF-A", 10, 2);
        UUID b = insertProduct("DIFF-B", 10, 2);
        UUID order = UUID.randomUUID();
        stockService.reserve(order, List.of(item(a, 2)));

        assertThatThrownBy(() -> stockService.reserve(order, List.of(item(a, 5))))
                .satisfies(t -> assertApiError(t, ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> stockService.reserve(order, List.of(item(a, 2), item(b, 1))))
                .satisfies(t -> assertApiError(t, ErrorCode.VALIDATION_ERROR));
        assertThat(stockOf(a)).isEqualTo(8);
        assertThat(stockOf(b)).isEqualTo(10);
    }

    @Test
    void anOrderRefThatWasReleasedCannotBeReservedAgain() {
        UUID a = insertProduct("REREL", 10, 2);
        UUID order = UUID.randomUUID();
        stockService.reserve(order, List.of(item(a, 4)));
        stockService.release(order);

        assertThatThrownBy(() -> stockService.reserve(order, List.of(item(a, 4))))
                .satisfies(t -> assertApiError(t, ErrorCode.INVALID_STATUS_TRANSITION));
        assertThat(stockOf(a)).isEqualTo(10); // no se devuelve un "exito" falso ni se vuelve a descontar
    }

    // ---------- liberacion ----------

    @Test
    void releaseRevertsExactlyWhatWasReserved() {
        UUID a = insertProduct("REL-A", 10, 2);
        UUID b = insertProduct("REL-B", 10, 2);
        UUID other = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        stockService.reserve(order, List.of(item(a, 3), item(b, 6)));
        stockService.reserve(other, List.of(item(a, 1))); // otro pedido: no debe tocarse
        assertThat(stockOf(a)).isEqualTo(6);

        stockService.release(order);

        assertThat(stockOf(a)).isEqualTo(9);
        assertThat(stockOf(b)).isEqualTo(10);
        assertThat(countMovements(order, MovementType.RELEASE)).isEqualTo(2);
        assertThat(countMovements(other, MovementType.RELEASE)).isZero();
    }

    @Test
    void releasingTwiceIsANoOp() {
        UUID a = insertProduct("REL2-A", 10, 2);
        UUID order = UUID.randomUUID();
        stockService.reserve(order, List.of(item(a, 3)));

        stockService.release(order);
        stockService.release(order);
        stockService.release(order);

        assertThat(stockOf(a)).isEqualTo(10); // nunca por encima del original
        assertThat(countMovements(order, MovementType.RELEASE)).isEqualTo(1);
    }

    @Test
    void releasingSomethingNeverReservedIsANoOp() {
        UUID a = insertProduct("REL3-A", 10, 2);
        stockService.release(UUID.randomUUID());
        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(countAllMovements()).isZero();
    }

    @Test
    void releaseWorksEvenIfTheProductWasDeactivatedAfterTheReservation() {
        UUID a = insertProduct("REL4-A", 10, 2);
        UUID order = UUID.randomUUID();
        stockService.reserve(order, List.of(item(a, 4)));
        jdbc.update("update products set active = false where id = ?", a); // baja logica

        stockService.release(order);

        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(activeOf(a)).isFalse(); // sigue de baja: solo se devuelve el stock
    }

    @Test
    void releaseRestoresReservationAfterTheStockWasAdjustedInBetween() {
        UUID a = insertProduct("REL5-A", 10, 2);
        UUID order = UUID.randomUUID();
        stockService.reserve(order, List.of(item(a, 4)));   // 6
        jdbc.update("update products set stock = 20 where id = ?", a); // ajuste externo

        stockService.release(order);

        assertThat(stockOf(a)).isEqualTo(24); // se revierte exactamente el movimiento registrado (+4)
    }

    // ---------- orden de bloqueo ----------

    @Test
    void reserveLocksRowsInAscendingProductIdOrderRegardlessOfRequestOrder() {
        List<UUID> sorted = new ArrayList<>(StockOrdering.ascending(List.of(
                insertProduct("LK-1", 10, 1), insertProduct("LK-2", 10, 1), insertProduct("LK-3", 10, 1),
                insertProduct("LK-4", 10, 1))));
        List<UUID> requestOrder = new ArrayList<>(sorted);
        Collections.reverse(requestOrder);
        clearInvocations(productRepository);

        stockService.reserve(UUID.randomUUID(), requestOrder.stream().map(id -> item(id, 1)).toList());

        InOrder inOrder = inOrder(productRepository);
        for (UUID id : sorted) {
            inOrder.verify(productRepository).findByIdForUpdate(id);
        }
    }

    @Test
    void releaseLocksRowsInAscendingProductIdOrderToo() {
        List<UUID> sorted = new ArrayList<>(StockOrdering.ascending(List.of(
                insertProduct("LKR-1", 10, 1), insertProduct("LKR-2", 10, 1), insertProduct("LKR-3", 10, 1))));
        List<UUID> requestOrder = new ArrayList<>(sorted);
        Collections.reverse(requestOrder);
        UUID order = UUID.randomUUID();
        stockService.reserve(order, requestOrder.stream().map(id -> item(id, 1)).toList());
        clearInvocations(productRepository);

        stockService.release(order);

        InOrder inOrder = inOrder(productRepository);
        for (UUID id : sorted) {
            inOrder.verify(productRepository).findByIdForUpdate(id);
        }
        verify(productRepository, org.mockito.Mockito.times(3)).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
    }
}
