package com.microinv.orders.dto;

import com.microinv.orders.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

/** Body de {@code PATCH /api/orders/{id}/status}. */
public record StatusChangeRequest(@NotNull OrderStatus status) {
}
