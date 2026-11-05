package com.microinv.auth.common;

import org.springframework.http.HttpStatus;

/** Codigos de error del contrato (seccion 5). El mensaje es breve y generico, en ingles. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username or password"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "Username is already taken"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email is already registered"),
    REGISTRATION_DISABLED(HttpStatus.FORBIDDEN, "Registration is disabled"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
