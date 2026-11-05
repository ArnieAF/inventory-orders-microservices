package com.microinv.orders.service;

import com.microinv.orders.domain.OrderEntity;
import com.microinv.orders.domain.OrderLine;
import com.microinv.orders.domain.OrderStatus;
import com.microinv.orders.dto.OrderSummaryResponse;
import com.microinv.orders.dto.OrdersSummaryResponse;
import com.microinv.orders.error.ApiException;
import com.microinv.orders.repository.OrderRepository;
import com.microinv.orders.security.CurrentUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceso transaccional a pedidos. Las llamadas HTTP a otros servicios NO ocurren aqui (se orquestan en
 * {@link OrderService}, fuera de cualquier transaccion, para no retener conexiones de BD mientras se espera).
 * Los eventos se publican dentro de la transaccion y se entregan solo tras el commit.
 */
@Service
public class OrderStore {

    private final OrderRepository repository;
    private final ApplicationEventPublisher events;

    public OrderStore(OrderRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /** Estado leido y validado antes de decidir si hay que liberar stock. */
    public record TransitionPlan(long expectedVersion, OrderStatus from) {
    }

    @Transactional
    public OrderEntity persistNew(UUID id, String customerName, String notes, List<OrderLine> lines,
                                  CurrentUser user) {
        OrderEntity order = OrderEntity.create(id, customerName, notes, user.id(), user.username(), lines, now());
        repository.saveAndFlush(order);
        events.publishEvent(new OrderCreatedEvent(order.getId(), user.id()));
        return order;
    }

    /** 404 si no existe, 409 INVALID_STATUS_TRANSITION si el contrato no permite la transicion. */
    @Transactional(readOnly = true)
    public TransitionPlan planTransition(UUID id, OrderStatus target) {
        OrderEntity order = repository.findById(id).orElseThrow(OrderStore::notFound);
        if (!order.getStatus().canTransitionTo(target)) {
            throw invalidTransition(order.getStatus(), target);
        }
        return new TransitionPlan(order.getVersion(), order.getStatus());
    }

    /**
     * Confirma el cambio de estado. Si otro proceso modifico el pedido desde {@link #planTransition}
     * (version distinta o conflicto de {@code @Version} al escribir) se responde 409 INVALID_STATUS_TRANSITION:
     * dos cambios concurrentes sobre el mismo pedido nunca ganan ambos.
     */
    @Transactional
    public OrderEntity applyTransition(UUID id, long expectedVersion, OrderStatus target, CurrentUser actor) {
        OrderEntity order = repository.findWithItemsById(id).orElseThrow(OrderStore::notFound);
        if (order.getVersion() != expectedVersion) {
            throw ApiException.invalidTransition("Order was modified concurrently; reload it and try again");
        }
        OrderStatus from = order.getStatus();
        if (!from.canTransitionTo(target)) {
            throw invalidTransition(from, target);
        }
        order.changeStatus(target, now());
        repository.saveAndFlush(order); // el UPDATE ... WHERE version = ? falla aqui si hubo carrera
        events.publishEvent(new OrderStatusChangedEvent(order.getId(), order.getCreatedBy(), actor.id(), from, target));
        return order;
    }

    @Transactional(readOnly = true)
    public OrderEntity get(UUID id) {
        return repository.findWithItemsById(id).orElseThrow(OrderStore::notFound);
    }

    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> page(OrderStatus status, Pageable pageable) {
        Page<OrderEntity> orders = status == null
                ? repository.findAll(pageable)
                : repository.findByStatus(status, pageable);
        Map<UUID, Integer> itemCounts = new HashMap<>();
        if (orders.hasContent()) {
            repository.countItems(orders.getContent().stream().map(OrderEntity::getId).toList())
                    .forEach(row -> itemCounts.put(row.getId(), row.getItemCount().intValue()));
        }
        return orders.map(order -> OrderSummaryResponse.from(order, itemCounts.getOrDefault(order.getId(), 0)));
    }

    @Transactional(readOnly = true)
    public OrdersSummaryResponse summary() {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        repository.countByStatus().forEach(row -> counts.put(row.getStatus(), row.getTotal().longValue()));
        return OrdersSummaryResponse.of(counts);
    }

    private static ApiException notFound() {
        return ApiException.notFound("Order not found");
    }

    private static ApiException invalidTransition(OrderStatus from, OrderStatus to) {
        return ApiException.invalidTransition("Cannot change order status from " + from + " to " + to);
    }

    /** Microsegundos: es la precision de timestamptz, asi la respuesta del POST coincide con la del GET. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
