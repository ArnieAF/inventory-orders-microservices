package com.microinv.orders.error;

import org.springframework.http.HttpStatus;

/**
 * Inventario caido, lento o con una respuesta inutilizable. Al cliente solo le llega un 503 generico.
 * <p>
 * {@code outcomeUnknown} indica que la peticion pudo llegar a inventario (timeout de lectura, 5xx,
 * respuesta invalida): en una reserva hay que intentar compensar con {@code release}.
 */
public class InventoryUnavailableException extends ApiException {

    private final boolean outcomeUnknown;

    public InventoryUnavailableException(boolean outcomeUnknown, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
                "Inventory service is unavailable", null, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }
}
