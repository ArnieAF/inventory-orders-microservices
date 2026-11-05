package com.microinv.orders.error;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Error de negocio con su traduccion HTTP en el formato comun del contrato (seccion 5).
 * Los mensajes son breves, genericos y en ingles; nunca incluyen detalles internos.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;
    private final List<Map<String, Object>> details;

    public ApiException(HttpStatus status, ErrorCode code, String message, List<Map<String, Object>> details,
                        Throwable cause) {
        // Sin traza de pila ni supresion: son errores esperables de control de flujo.
        super(message, cause, false, false);
        this.status = status;
        this.code = code;
        this.details = details == null || details.isEmpty() ? null : List.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    /** Detalles opcionales; {@code null} si no hay. */
    public List<Map<String, Object>> details() {
        return details;
    }

    public static ApiException validation(String message, List<Map<String, Object>> details) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message, details, null);
    }

    public static ApiException validation(String field, String message) {
        return validation("Validation failed", List.of(Map.of("field", field, "message", message)));
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message, null, null);
    }

    public static ApiException invalidTransition(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_TRANSITION, message, null, null);
    }

    public static ApiException internal() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Unexpected error", null, null);
    }
}
