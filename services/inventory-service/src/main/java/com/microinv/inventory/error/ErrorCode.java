package com.microinv.inventory.error;

import org.springframework.http.HttpStatus;

/** Codigos de error del contrato (seccion 5) que usa este servicio. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    SKU_ALREADY_EXISTS(HttpStatus.CONFLICT),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT),
    PRODUCT_INACTIVE(HttpStatus.CONFLICT),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Codigo del contrato para un estado HTTP generado por el framework o el contenedor. */
    public static ErrorCode forStatus(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return NOT_FOUND;
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            return UNAUTHORIZED;
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return SERVICE_UNAVAILABLE;
        }
        return status.is5xxServerError() ? INTERNAL_ERROR : VALIDATION_ERROR;
    }
}
