package com.microinv.orders.error;

/** Subconjunto de los codigos de error del contrato (seccion 5) que emite este servicio. */
public enum ErrorCode {
    VALIDATION_ERROR,
    UNAUTHORIZED,
    NOT_FOUND,
    INSUFFICIENT_STOCK,
    PRODUCT_INACTIVE,
    INVALID_STATUS_TRANSITION,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
