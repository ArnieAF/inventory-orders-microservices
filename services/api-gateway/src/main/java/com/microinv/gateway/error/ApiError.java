package com.microinv.gateway.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Formato comun de error (contrato seccion 5), tambien para los errores que genera el gateway. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String code, String message, String path) {

    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(Instant.now().truncatedTo(ChronoUnit.MILLIS), status, code, message, path);
    }
}
