package com.microinv.orders.dto;

import com.microinv.orders.domain.OrderEntity;
import com.microinv.orders.domain.OrderItemEntity;
import com.microinv.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** {@code Order} del contrato 6.3. */
public record OrderResponse(
        UUID id,
        String customerName,
        String notes,
        OrderStatus status,
        BigDecimal total,
        List<OrderItemResponse> items,
        UUID createdBy,
        String createdByUsername,
        Instant createdAt,
        Instant updatedAt) {

    /** Los items salen ordenados por sku y productId, igual en el POST y en el GET (no hay columna de posicion). */
    private static final Comparator<OrderItemEntity> ITEM_ORDER =
            Comparator.comparing(OrderItemEntity::getSku).thenComparing(OrderItemEntity::getProductId);

    public static OrderResponse from(OrderEntity order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .sorted(ITEM_ORDER)
                .map(OrderItemResponse::from)
                .toList();
        return new OrderResponse(order.getId(), order.getCustomerName(), order.getNotes(), order.getStatus(),
                order.getTotal(), items, order.getCreatedBy(), order.getCreatedByUsername(), order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
