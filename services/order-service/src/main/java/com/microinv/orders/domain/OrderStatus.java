package com.microinv.orders.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estados del pedido y maquina de estados del contrato (seccion 6.3):
 * PENDING a CONFIRMED|CANCELLED, CONFIRMED a SHIPPED|CANCELLED, SHIPPED a DELIVERED.
 * DELIVERED y CANCELLED son terminales.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(PENDING, EnumSet.of(CONFIRMED, CANCELLED));
        TRANSITIONS.put(CONFIRMED, EnumSet.of(SHIPPED, CANCELLED));
        TRANSITIONS.put(SHIPPED, EnumSet.of(DELIVERED));
        TRANSITIONS.put(DELIVERED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    public boolean canTransitionTo(OrderStatus target) {
        return target != null && TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
