package com.microinv.auth.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Formato comun de error (contrato seccion 5). Nunca incluye stack traces, SQL ni nombres de clases. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String code, String message, String path, List<Detail> details) {

    public record Detail(String field, String message) {
    }

    public static ApiError of(int status, String code, String message, String path, List<Detail> details) {
        return new ApiError(Instant.now().truncatedTo(ChronoUnit.MILLIS), status, code, message, path,
                details == null || details.isEmpty() ? null : details);
    }

    public static ApiError of(ErrorCode code, String path) {
        return of(code.status().value(), code.name(), code.defaultMessage(), path, null);
    }
}
