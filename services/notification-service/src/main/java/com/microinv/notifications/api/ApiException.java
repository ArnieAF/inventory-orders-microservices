package com.microinv.notifications.api;

import java.util.List;
import org.springframework.http.HttpStatus;

/** Error de negocio/entrada con su respuesta ya decidida (mensaje seguro para el cliente). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiError.Detail> details;

    public ApiException(HttpStatus status, String code, String message, List<ApiError.Detail> details) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "Resource not found", null);
    }

    public static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Request validation failed",
                List.of(new ApiError.Detail(field, message)));
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ApiError.Detail> getDetails() {
        return details;
    }
}
