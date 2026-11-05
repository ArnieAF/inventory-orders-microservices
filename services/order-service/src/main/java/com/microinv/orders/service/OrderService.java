package com.microinv.orders.service;

import com.microinv.orders.client.InventoryClient;
import com.microinv.orders.client.ReserveItem;
import com.microinv.orders.client.ReserveResult;
import com.microinv.orders.domain.OrderEntity;
import com.microinv.orders.domain.OrderLine;
import com.microinv.orders.domain.OrderStatus;
import com.microinv.orders.dto.CreateOrderRequest;
import com.microinv.orders.dto.ItemRequest;
import com.microinv.orders.dto.OrderResponse;
import com.microinv.orders.dto.OrderSummaryResponse;
import com.microinv.orders.dto.OrdersSummaryResponse;
import com.microinv.orders.dto.PageResponse;
import com.microinv.orders.error.ApiException;
import com.microinv.orders.error.InventoryUnavailableException;
import com.microinv.orders.error.OrderModifiedConcurrentlyException;
import com.microinv.orders.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Orquesta los flujos que cruzan servicios. NO es transaccional a proposito: las llamadas HTTP a inventario
 * ocurren fuera de cualquier transaccion de BD; las transacciones cortas viven en {@link OrderStore}.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** Reintentos de la confirmacion de una cancelacion cuyo stock ya se libero. */
    static final int MAX_CANCEL_RETRIES = 2;

    private final InventoryClient inventory;
    private final OrderStore store;

    public OrderService(InventoryClient inventory, OrderStore store) {
        this.inventory = inventory;
        this.store = store;
    }

    /**
     * Flujo de creacion (contrato 6.3): generar orderId, reservar stock con {@code orderRef=orderId}, guardar el
     * pedido PENDING con la instantanea de inventario y, tras el commit, notificar NEW_ORDER (lo hace el
     * oyente AFTER_COMMIT de {@link NotificationDispatcher}).
     */
    public OrderResponse create(CreateOrderRequest request, CurrentUser user) {
        List<ItemRequest> items = request.items();
        OrderLines.rejectDuplicates(items);

        UUID orderId = UUID.randomUUID();
        List<ReserveItem> reserveItems = items.stream()
                .map(item -> new ReserveItem(item.productId(), item.quantity()))
                .toList();

        ReserveResult reserved;
        try {
            reserved = inventory.reserve(orderId, reserveItems);
        } catch (InventoryUnavailableException e) {
            // Si la peticion pudo llegar a inventario (timeout de lectura, 5xx) no sabemos si reservo: compensar.
            if (e.isOutcomeUnknown()) {
                compensate(orderId);
            }
            throw e;
        }
        // Un rechazo definitivo (InventoryRejectedException) se propaga tal cual: no se reservo nada.

        OrderEntity saved;
        try {
            List<OrderLine> lines = OrderLines.fromReservation(items, reserved);
            saved = store.persistNew(orderId, request.customerName().trim(), normalizeNotes(request.notes()),
                    lines, user);
        } catch (RuntimeException e) {
            // Hay stock reservado y no hay pedido: devolverlo. Best-effort, pero se registra si tambien falla.
            compensate(orderId);
            if (e instanceof ApiException) {
                throw e;
            }
            // La causa real queda en el log del servidor (con la excepcion completa); el cliente solo recibe el 500
            // generico.
            log.error("Order {} could not be saved after reserving stock; stock release attempted", orderId, e);
            throw ApiException.internal();
        }
        // Ya confirmado en BD: a partir de aqui nada puede disparar la compensacion.
        return OrderResponse.from(saved);
    }

    /**
     * Cambio de estado. Hacia CANCELLED: validar la transicion, liberar stock (idempotente) y confirmar el estado;
     * si la liberacion falla se responde 503 y el pedido NO cambia.
     */
    public OrderResponse changeStatus(UUID id, OrderStatus target, CurrentUser actor) {
        OrderStore.TransitionPlan plan = store.planTransition(id, target); // 404 / 409 sin tocar inventario

        if (target == OrderStatus.CANCELLED) {
            inventory.release(id); // 503 si falla: el pedido queda como estaba
            return confirmCancellation(id, plan.expectedVersion(), actor);
        }
        return OrderResponse.from(store.applyTransition(id, plan.expectedVersion(), target, actor));
    }

    /**
     * Confirma CANCELLED cuando el stock YA se libero. Si otro usuario cambio el pedido entre medias (version
     * distinta) no basta con rendirse, porque quedaria stock liberado con el pedido activo. Se relee el estado:
     * <ul>
     *   <li>ya CANCELLED: exito idempotente (otra peticion lo cancelo; no se notifica de nuevo);</li>
     *   <li>aun cancelable (PENDING/CONFIRMED): se reintenta {@code applyTransition} con la version nueva, como
     *       maximo {@link #MAX_CANCEL_RETRIES} veces y SIN repetir el release (ya hecho e idempotente);</li>
     *   <li>ya no cancelable (SHIPPED/DELIVERED): inconsistencia real; se registra a nivel ERROR con un
     *       marcador de conciliacion y se mantiene el 409.</li>
     * </ul>
     */
    private OrderResponse confirmCancellation(UUID id, long expectedVersion, CurrentUser actor) {
        long version = expectedVersion;
        for (int retry = 0; ; retry++) {
            try {
                return OrderResponse.from(store.applyTransition(id, version, OrderStatus.CANCELLED, actor));
            } catch (OrderModifiedConcurrentlyException | ConcurrencyFailureException conflict) {
                OrderStore.OrderState current = store.currentState(id);
                if (current.status() == OrderStatus.CANCELLED) {
                    log.info("Order {} was already cancelled by a concurrent request", id);
                    return OrderResponse.from(store.get(id));
                }
                if (!current.status().canTransitionTo(OrderStatus.CANCELLED)) {
                    logStockReleasedButOrderActive(id, current.status());
                    throw ApiException.invalidTransition(
                            "Cannot change order status from " + current.status() + " to CANCELLED");
                }
                if (retry >= MAX_CANCEL_RETRIES) {
                    logStockReleasedButOrderActive(id, current.status());
                    throw conflict;
                }
                version = current.version();
                log.warn("Order {} changed concurrently while cancelling (now {}); retrying the confirmation ({}/{})",
                        id, current.status(), retry + 1, MAX_CANCEL_RETRIES);
            } catch (RuntimeException e) {
                // Fallo al confirmar que no es un conflicto de version (p. ej. BD caida): limitacion conocida del
                // contrato (sin saga/outbox). Reintentar la cancelacion lo resuelve.
                log.error("RECONCILE order={}: stock released but order still active; cancellation not confirmed ({})",
                        id, e.getClass().getSimpleName());
                throw e;
            }
        }
    }

    private void logStockReleasedButOrderActive(UUID id, OrderStatus status) {
        log.error("RECONCILE order={}: stock released but order still active (status {}); manual reconciliation "
                + "needed", id, status);
    }

    public OrderResponse get(UUID id) {
        return OrderResponse.from(store.get(id));
    }

    public PageResponse<OrderSummaryResponse> list(OrderStatus status, Pageable pageable) {
        return PageResponse.of(store.page(status, pageable));
    }

    public OrdersSummaryResponse summary() {
        return store.summary();
    }

    private void compensate(UUID orderId) {
        try {
            inventory.release(orderId);
        } catch (Exception e) {
            // Requiere conciliacion manual: queda stock reservado para un pedido que no existe.
            log.error("Stock release (compensation) FAILED for order {}: {}. Manual reconciliation may be needed",
                    orderId, e.getClass().getSimpleName());
        }
    }

    private static String normalizeNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        return notes.trim();
    }
}
