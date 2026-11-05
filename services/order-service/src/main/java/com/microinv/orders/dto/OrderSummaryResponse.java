package com.microinv.orders.dto;

import com.microinv.orders.domain.OrderEntity;
import com.microinv.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code OrderSummary} del contrato 6.3 (fila de listado). */
public record OrderSummaryResponse(
        UUID id,
        String customerName,
        OrderStatus status,
        BigDecimal total,
        int itemCount,
        String createdByUsername,
        Instant createdAt) {

    public static OrderSummaryResponse from(OrderEntity order, int itemCount) {
        return new OrderSummaryResponse(order.getId(), order.getCustomerName(), order.getStatus(), order.getTotal(),
                itemCount, order.getCreatedByUsername(), order.getCreatedAt());
    }
}
