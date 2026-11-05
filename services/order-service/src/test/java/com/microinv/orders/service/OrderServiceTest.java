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

import com.microinv.orders.client.InventoryClient;
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

    @BeforeEach
    void setUp() {
        inventory = mock(InventoryClient.class);
        store = mock(OrderStore.class);
        service = new OrderService(inventory, store);
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

    @Test
    void concurrencyFailureWhileConfirmingACancellationPropagates() {
        UUID id = UUID.randomUUID();
        when(store.planTransition(id, OrderStatus.CANCELLED))
                .thenReturn(new OrderStore.TransitionPlan(0L, OrderStatus.PENDING));
        when(store.applyTransition(any(), anyLong(), any(), any()))
                .thenThrow(new OptimisticLockingFailureException("stale"));

        assertThatThrownBy(() -> service.changeStatus(id, OrderStatus.CANCELLED, USER))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(inventory).release(id);
    }
}
