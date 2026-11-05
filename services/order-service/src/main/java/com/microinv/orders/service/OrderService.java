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
import com.microinv.orders.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Orquesta los flujos que cruzan servicios. NO es transaccional a proposito: las llamadas HTTP a inventario
 * ocurren fuera de cualquier transaccion de BD; las transacciones cortas viven en {@link OrderStore}.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

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
            // Solo clases de excepcion en el log normal: el detalle de una excepcion de BD puede incluir valores.
            log.error("Order {} could not be saved after reserving stock ({}); stock release attempted",
                    orderId, e.getClass().getSimpleName());
            log.debug("Order save failure detail", e);
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
        }

        try {
            return OrderResponse.from(store.applyTransition(id, plan.expectedVersion(), target, actor));
        } catch (RuntimeException e) {
            if (target == OrderStatus.CANCELLED) {
                // Limitacion conocida del contrato (sin saga/outbox): stock liberado y pedido aun activo.
                log.warn("Order {} cancellation was not confirmed after releasing stock ({}); retrying the "
                        + "cancellation resolves it", id, e.getClass().getSimpleName());
            }
            throw e;
        }
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
