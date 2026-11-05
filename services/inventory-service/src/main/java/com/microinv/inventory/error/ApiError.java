package com.microinv.inventory.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Formato comun de error (contrato 5). {@code details} es opcional. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String code, String message, String path, List<?> details) {

    public static ApiError of(int status, String code, String message, String path, List<?> details) {
        return new ApiError(Instant.now().truncatedTo(ChronoUnit.MILLIS), status, code, message, path,
                details == null || details.isEmpty() ? null : details);
    }
}
