package com.microinv.orders.dto;

import com.microinv.orders.domain.OrderStatus;
import java.util.EnumMap;
import java.util.Map;

/**
 * {@code GET /api/orders/summary}: {@code total} es el numero total de pedidos (suma de {@code byStatus}),
 * y {@code byStatus} incluye siempre los cinco estados (0 si no hay pedidos).
 */
public record OrdersSummaryResponse(long total, Map<OrderStatus, Long> byStatus) {

    public static OrdersSummaryResponse of(Map<OrderStatus, Long> counts) {
        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        long total = 0;
        for (OrderStatus status : OrderStatus.values()) {
            long count = counts.getOrDefault(status, 0L);
            byStatus.put(status, count);
            total += count;
        }
        return new OrdersSummaryResponse(total, byStatus);
    }
}
