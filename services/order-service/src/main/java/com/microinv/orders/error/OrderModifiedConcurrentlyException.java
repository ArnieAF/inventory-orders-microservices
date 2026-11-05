package com.microinv.orders.error;

import org.springframework.http.HttpStatus;

/**
 * Otro proceso modifico el pedido entre que se leyo y se escribio (version distinta). Se responde 409
 * INVALID_STATUS_TRANSITION; {@code OrderService} la distingue de una transicion invalida para poder reintentar
 * una cancelacion cuyo stock ya se libero.
 */
public class OrderModifiedConcurrentlyException extends ApiException {

    public OrderModifiedConcurrentlyException() {
        super(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_TRANSITION,
                "Order was modified concurrently; reload it and try again", null, null);
    }
}
