package com.microinv.orders.client;

/** Tipos de notificacion que emite order-service (el contrato 6.4 define ademas LOW_STOCK, de inventario). */
public enum NotificationType {
    NEW_ORDER,
    ORDER_STATUS_CHANGED
}
