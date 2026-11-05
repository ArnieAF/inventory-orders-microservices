package com.microinv.notifications.api;

/** Códigos de error del contrato (§5) que usa este servicio. */
public final class ErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }

    /** Código del contrato para un estado HTTP. Los estados que el contrato no cubre usan el más cercano. */
    public static String forStatus(int status) {
        if (status == 401) {
            return UNAUTHORIZED;
        }
        if (status == 404) {
            return NOT_FOUND;
        }
        if (status >= 500) {
            return INTERNAL_ERROR;
        }
        return VALIDATION_ERROR;
    }

    /** Mensaje breve y genérico en inglés para un estado HTTP. */
    public static String messageForStatus(int status) {
        return switch (status) {
            case 400 -> "Request validation failed";
            case 401 -> "Authentication is required";
            case 404 -> "Resource not found";
            case 405 -> "Method not allowed";
            case 406 -> "Not acceptable";
            case 415 -> "Unsupported media type";
            default -> status >= 500 ? "Internal server error" : "Request could not be processed";
        };
    }
}
