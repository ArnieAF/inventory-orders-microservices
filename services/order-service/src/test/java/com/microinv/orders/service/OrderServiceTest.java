package com.microinv.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microinv.orders.client.InventoryClient;
import com.microinv.orders.error.OrderModifiedConcurrentlyException;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import com.microinv.orders.client.ReserveResult;
import com.microinv.orders.client.ReservedItem;
import com.microinv.orders.domain.OrderEntity;
import com.microinv.orders.domain.OrderLine;
import com.microinv.orders.domain.OrderStatus;
import com.microinv.orders.dto.CreateOrderRequest;
import com.microinv.orders.dto.ItemRequest;
import com.microinv.orders.dto.OrderResponse;
import com.microinv.orders.error.ApiException;
import com.microinv.orders.error.ErrorCode;
import com.microinv.orders.error.InventoryRejectedException;
import com.microinv.orders.error.InventoryUnavailableException;
import com.microinv.orders.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.OptimisticLockingFailureException;

/** Logica de orquestacion (compensaciones y orden de llamadas) sin Spring, con inventario y almacen simulados. */
class OrderServiceTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final CurrentUser USER = new CurrentUser(UUID.randomUUID(), "alice");

    private InventoryClient inventory;
    private OrderStore store;
    private OrderService service;
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(OrderService.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        inventory = mock(InventoryClient.class);
        store = mock(OrderStore.class);
        service = new OrderService(inventory, store);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        serviceLogger.detachAppender(logs);
    }

    private static CreateOrderRequest request() {
        return new CreateOrderRequest("  ACME ", " note ", List.of(new ItemRequest(PRODUCT, 2)));
    }

    private void reserveOk() {
        when(inventory.reserve(any(), anyList())).thenReturn(new ReserveResult(
                List.of(new ReservedItem(PRODUCT, "SKU", "Name", new BigDecimal("5.00"), 2))));
    }

    private static OrderEntity entity(UUID id) {
        return OrderEntity.create(id, "ACME", "note", USER.id(), USER.username(),
                List.of(new OrderLine(PRODUCT, "SKU", "Name", new BigDecimal("5.00"), 2)), Instant.now());
    }

    // ---- creacion ---------------------------------------------------------------------------------------------

    @Test
    void createReservesThenSavesWithTrimmedFieldsAndNeverReleasesOnSuccess() {
        reserveOk();
        when(store.persistNew(any(), any(), any(), anyList(), any())).thenAnswer(
                invocation -> entity(invocation.getArgument(0)));

        OrderResponse response = service.create(request(), USER);

        ArgumentCaptor<UUID> orderRef = ArgumentCaptor.forClass(UUID.class);
        InOrder order = inOrder(inventory, store);
        order.verify(inventory).reserve(orderRef.capture(), anyList());
        order.verify(store).persistNew(eq(orderRef.getValue()), eq("ACME"), eq("note"), anyList(), eq(USER));
        verify(inventory, never()).release(any());
        assertThat(response.id()).isEqualTo(orderRef.getValue());
        assertThat(response.total()).isEqualByComparingTo("10.00");
    }

    @Test
    void failureWhileSavingReleasesWithTheSameOrderRefAndReturnsGenericInternalError() {
        reserveOk();
        when(store.persistNew(any(), any(), any(), anyList(), any()))
                .thenThrow(new DataIntegrityViolationException("could not execute statement [secret sql detail]"));

        assertThatThrownBy(() -> service.create(request(), USER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(e.getMessage()).doesNotContain("secret").doesNotContain("sql");
                });

        ArgumentCaptor<UUID> reserved = ArgumentCaptor.forClass(UUID.class);
        verify(inventory).reserve(reserved.capture(), anyList());
        verify(inventory, times(1)).release(reserved.getValue());
    }

    @Test
    void failedCompensationIsSwallowedAndTheGenericErrorStillComesBack() {
        reserveOk();
        when(store.persistNew(any(), any(), any(), anyList(), any())).thenThrow(new IllegalStateException("db down"));
        doThrow(new InventoryUnavailableException(true, null)).when(inventory).release(any());

        assertThatThrownBy(() -> service.create(request(), USER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        verify(inventory, times(1)).release(any());
    }

    @Test
    void unknownReserveOutcomeIsCompensatedButDefinitiveFailuresAreNot() {
        when(inventory.reserve(any(), anyList())).thenThrow(new InventoryUnavailableException(true, null));
        assertThatThrownBy(() -> service.create(request(), USER)).isInstanceOf(InventoryUnavailableException.class);
        verify(inventory, times(1)).release(any());

        InventoryClient neverReached = mock(InventoryClient.class);
        when(neverReached.reserve(any(), anyList())).thenThrow(new InventoryUnavailableException(false, null));
        OrderService other = new OrderService(neverReached, store);
        assertThatThrownBy(() -> other.create(request(), USER)).isInstanceOf(InventoryUnavailableException.class);
        verify(neverReached, never()).release(any());

        InventoryClient rejecting = mock(InventoryClient.class);
        when(rejecting.reserve(any(), anyList())).thenThrow(new InventoryRejectedException(HttpStatus.CONFLICT,
                ErrorCode.INSUFFICIENT_STOCK, "m", null));
        OrderService third = new OrderService(rejecting, store);
        assertThatThrownBy(() -> third.create(request(), USER)).isInstanceOf(InventoryRejectedException.class);
        verify(rejecting, never()).release(any());
        verifyNoInteractions(store);
    }

    @Test
    void invalidInventorySnapshotIsCompensatedAndNothingIsSaved() {
        when(inventory.reserve(any(), anyList())).thenReturn(new ReserveResult(List.of()));

        assertThatThrownBy(() -> service.create(request(), USER)).isInstanceOf(InventoryUnavailableException.class);

        verify(inventory, times(1)).release(any());
        verifyNoInteractions(store);
    }

    @Test
    void repeatedProductsAreRejectedBeforeAnyCall() {
        CreateOrderRequest duplicated = new CreateOrderRequest("ACME", null,
                List.of(new ItemRequest(PRODUCT, 1), new ItemRequest(PRODUCT, 2)));

        assertThatThrownBy(() -> service.create(duplicated, USER)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verifyNoInteractions(inventory);
        verifyNoInteractions(store);
    }

    // ---- cambio de estado -------------------------------------------------------------------------------------

    @Test
    void cancellingValidatesThenReleasesThenConfirms() {
        UUID id = UUID.randomUUID();
        when(store.planTransition(id, OrderStatus.CANCELLED))
                .thenReturn(new OrderStore.TransitionPlan(3L, OrderStatus.CONFIRMED));
        when(store.applyTransition(eq(id), eq(3L), eq(OrderStatus.CANCELLED), eq(USER))).thenReturn(entity(id));

        service.changeStatus(id, OrderStatus.CANCELLED, USER);

        InOrder order = inOrder(store, inventory);
        order.verify(store).planTransition(id, OrderStatus.CANCELLED);
        order.verify(inventory).release(id);
        order.verify(store).applyTransition(id, 3L, OrderStatus.CANCELLED, USER);
    }

    @Test
    void ifReleaseFailsTheStateIsNeverConfirmed() {
        UUID id = UUID.randomUUID();
        when(store.planTransition(id, OrderStatus.CANCELLED))
                .thenReturn(new OrderStore.TransitionPlan(0L, OrderStatus.PENDING));
        doThrow(new InventoryUnavailableException(true, null)).when(inventory).release(id);

        assertThatThrownBy(() -> service.changeStatus(id, OrderStatus.CANCELLED, USER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                });

        verify(store, never()).applyTransition(any(), anyLong(), any(), any());
    }

    @Test
    void invalidTransitionNeverTouchesInventory() {
        UUID id = UUID.randomUUID();
        when(store.planTransition(id, OrderStatus.CANCELLED)).thenThrow(ApiException.invalidTransition("nope"));

        assertThatThrownBy(() -> service.changeStatus(id, OrderStatus.CANCELLED, USER))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));

        verifyNoInteractions(inventory);
    }

    @Test
    void nonCancellationTransitionsNeverCallInventory() {
        UUID id = UUID.randomUUID();
        for (OrderStatus target : List.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.DELIVERED)) {
            when(store.planTransition(id, target)).thenReturn(new OrderStore.TransitionPlan(0L, OrderStatus.PENDING));
            when(store.applyTransition(eq(id), eq(0L), eq(target), eq(USER))).thenReturn(entity(id));
            service.changeStatus(id, target, USER);
        }
        verifyNoInteractions(inventory);
    }

    // ---- cancelacion con stock ya liberado y conflicto de version ---------------------------------------------------

    private void planCancellationFromVersion(UUID id, long version, OrderStatus from) {
        when(store.planTransition(id, OrderStatus.CANCELLED)).thenReturn(new OrderStore.TransitionPlan(version, from));
    }

    private List<String> errorLogs() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void ifAnotherRequestAlreadyCancelledTheOrderTheResultIsAnIdempotentSuccess() {
        UUID id = UUID.randomUUID();
        planCancellationFromVersion(id, 0L, OrderStatus.PENDING);
        when(store.applyTransition(eq(id), eq(0L), eq(OrderStatus.CANCELLED), eq(USER)))
                .thenThrow(new OrderModifiedConcurrentlyException());
        when(store.currentState(id)).thenReturn(new OrderStore.OrderState(OrderStatus.CANCELLED, 1L));
        when(store.get(id)).thenReturn(entity(id));

        OrderResponse response = service.changeStatus(id, OrderStatus.CANCELLED, USER);

        assertThat(response.id()).isEqualTo(id);
        verify(inventory, times(1)).release(id);
        verify(store, times(1)).applyTransition(any(), anyLong(), any(), any()); // sin segundo intento
        assertThat(errorLogs()).isEmpty();
    }

    @Test
    void ifTheOrderChangedButIsStillCancellableItRetriesWithTheNewVersionWithoutRepeatingTheRelease() {
        UUID id = UUID.randomUUID();
        planCancellationFromVersion(id, 0L, OrderStatus.PENDING);
        when(store.applyTransition(eq(id), eq(0L), eq(OrderStatus.CANCELLED), eq(USER)))
                .thenThrow(new OptimisticLockingFailureException("stale"));
        when(store.currentState(id)).thenReturn(new OrderStore.OrderState(OrderStatus.CONFIRMED, 1L));
        when(store.applyTransition(eq(id), eq(1L), eq(OrderStatus.CANCELLED), eq(USER))).thenReturn(entity(id));

        OrderResponse response = service.changeStatus(id, OrderStatus.CANCELLED, USER);

        assertThat(response.id()).isEqualTo(id);
        verify(inventory, times(1)).release(id); // el release es idempotente y ya se hizo
        InOrder order = inOrder(store);
        order.verify(store).applyTransition(id, 0L, OrderStatus.CANCELLED, USER);
        order.verify(store).currentState(id);
        order.verify(store).applyTransition(id, 1L, OrderStatus.CANCELLED, USER);
        assertThat(errorLogs()).isEmpty();
    }

    @Test
    void retriesAreBoundedToTwoAndTheExhaustedCaseIsLoggedAsReconciliationError() {
        UUID id = UUID.randomUUID();
        planCancellationFromVersion(id, 0L, OrderStatus.PENDING);
        when(store.applyTransition(eq(id), anyLong(), eq(OrderStatus.CANCELLED), eq(USER)))
                .thenThrow(new OrderModifiedConcurrentlyException());
        when(store.currentState(id)).thenReturn(new OrderStore.OrderState(OrderStatus.CONFIRMED, 1L),
                new OrderStore.OrderState(OrderStatus.CONFIRMED, 2L),
                new OrderStore.OrderState(OrderStatus.CONFIRMED, 3L));

        assertThatThrownBy(() -> service.changeStatus(id, OrderStatus.CANCELLED, USER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
                });

        assertThat(OrderService.MAX_CANCEL_RETRIES).isEqualTo(2);
        verify(store, times(3)).applyTransition(any(), anyLong(), any(), any()); // 1 intento + 2 reintentos
        verify(inventory, times(1)).release(id);
        assertThat(errorLogs()).singleElement().satisfies(message -> {
            assertThat(message).contains(id.toString()).contains("stock released but order still active");
        });
    }

    @Test
    void ifTheOrderIsNoLongerCancellableTheReconciliationErrorIsLoggedAndTheConflictIsKept() {
        for (OrderStatus status : List.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED)) {
            logs.list.clear();
            UUID id = UUID.randomUUID();
            planCancellationFromVersion(id, 0L, OrderStatus.CONFIRMED);
            when(store.applyTransition(eq(id), anyLong(), eq(OrderStatus.CANCELLED), eq(USER)))
                    .thenThrow(new OptimisticLockingFailureException("stale"));
            when(store.currentState(id)).thenReturn(new OrderStore.OrderState(status, 5L));

            assertThatThrownBy(() -> service.changeStatus(id, OrderStatus.CANCELLED, USER))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
                        assertThat(e.getMessage()).doesNotContain("stock");
                    });

            verify(store, times(1)).applyTransition(eq(id), anyLong(), any(), any()); // no se reintenta
            assertThat(errorLogs()).singleElement().satisfies(message -> assertThat(message)
                    .contains(id.toString()).contains("stock released but order still active")
                    .contains(status.name()));
        }
    }

    @Test
    void anyOtherFailureConfirmingAReleasedCancellationIsAlsoLoggedForReconciliation() {
        UUID id = UUID.randomUUID();
        planCancellationFromVersion(id, 0L, OrderStatus.PENDING);
        when(store.applyTransition(any(), anyLong(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.changeStatus(id, OrderStatus.CANCELLED, USER))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

        verify(store, never()).currentState(any());
        assertThat(errorLogs()).singleElement().satisfies(message -> assertThat(message)
                .contains(id.toString()).contains("stock released but order still active"));
    }

    @Test
    void nonCancellationConflictsAreNotRetriedNorLoggedAsReconciliation() {
        UUID id = UUID.randomUUID();
        when(store.planTransition(id, OrderStatus.CONFIRMED))
                .thenReturn(new OrderStore.TransitionPlan(0L, OrderStatus.PENDING));
        when(store.applyTransition(any(), anyLong(), any(), any())).thenThrow(new OrderModifiedConcurrentlyException());

        assertThatThrownBy(() -> service.changeStatus(id, OrderStatus.CONFIRMED, USER))
                .isInstanceOf(OrderModifiedConcurrentlyException.class);

        verify(store, times(1)).applyTransition(any(), anyLong(), any(), any());
        verifyNoInteractions(inventory);
        assertThat(errorLogs()).isEmpty();
    }

    // ---- registro de la causa real al fallar el guardado ------------------------------------------------------

    @Test
    void theRealCauseOfASaveFailureIsLoggedAtErrorLevelWithTheFullException() {
        reserveOk();
        DataIntegrityViolationException cause = new DataIntegrityViolationException("constraint ck_orders_total");
        when(store.persistNew(any(), any(), any(), anyList(), any())).thenThrow(cause);

        assertThatThrownBy(() -> service.create(request(), USER)).isInstanceOf(ApiException.class);

        ILoggingEvent event = logs.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("could not be saved"))
                .findFirst().orElseThrow();
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getMessage()).contains("ck_orders_total");
        assertThat(event.getThrowableProxy().getClassName()).isEqualTo(DataIntegrityViolationException.class.getName());
    }
}
