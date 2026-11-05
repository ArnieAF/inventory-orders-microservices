package com.microinv.orders.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Formato comun de error del contrato (seccion 5). {@code details} solo se serializa si existe. */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Map<String, Object>> details) {

    public static ErrorResponse of(int status, ErrorCode code, String message, String path,
                                   List<Map<String, Object>> details) {
        return new ErrorResponse(Instant.now().truncatedTo(ChronoUnit.MILLIS), status, code.name(), message, path,
                details == null || details.isEmpty() ? null : details);
    }
}
