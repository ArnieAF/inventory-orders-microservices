package com.microinv.notifications.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Formato común de error (§5). Nunca incluye trazas, SQL ni nombres de clases. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String code, String message, String path,
                       List<Detail> details) {

    public record Detail(String field, String message) {
    }

    public static ApiError of(int status, String code, String message, String path, List<Detail> details) {
        return new ApiError(Instant.now(), status, code, message, path,
                details == null || details.isEmpty() ? null : details);
    }

    public static ApiError of(int status, String path) {
        return of(status, ErrorCodes.forStatus(status), ErrorCodes.messageForStatus(status), path, null);
    }
}
