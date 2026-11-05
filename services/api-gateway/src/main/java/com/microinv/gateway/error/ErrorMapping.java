package com.microinv.gateway.error;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * Traduce excepciones del gateway a (status HTTP, codigo del contrato). Logica pura.
 * <ul>
 *   <li>404 -> NOT_FOUND, 401 -> UNAUTHORIZED, 400 -> VALIDATION_ERROR;</li>
 *   <li>fallo del servicio destino (conexion rechazada, timeout, 502/503/504 del propio gateway)
 *       -> 503 SERVICE_UNAVAILABLE (contrato seccion 3);</li>
 *   <li>cualquier otra cosa -> 500 INTERNAL_ERROR generico.</li>
 * </ul>
 */
public final class ErrorMapping {

    public record Failure(HttpStatusCode status, ErrorCode code) {
    }

    private static final int MAX_CAUSE_DEPTH = 16;

    private ErrorMapping() {
    }

    public static Failure classify(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return fromStatus(rse.getStatusCode());
        }
        if (isUpstreamFailure(ex)) {
            return new Failure(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE);
        }
        return new Failure(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    }

    static Failure fromStatus(HttpStatusCode status) {
        int value = status.value();
        return switch (value) {
            case 400 -> new Failure(status, ErrorCode.VALIDATION_ERROR);
            case 401 -> new Failure(status, ErrorCode.UNAUTHORIZED);
            case 404 -> new Failure(status, ErrorCode.NOT_FOUND);
            case 502, 503, 504 -> new Failure(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE);
            default -> {
                if (value >= 400 && value < 500) {
                    // El contrato no define mas codigos 4xx: se conserva el status.
                    yield new Failure(status, ErrorCode.VALIDATION_ERROR);
                }
                yield new Failure(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
            }
        };
    }

    static boolean isUpstreamFailure(Throwable ex) {
        Throwable t = ex;
        // Profundidad acotada: protege frente a cadenas de causas ciclicas.
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof IOException
                    || t instanceof TimeoutException
                    || t instanceof io.netty.handler.timeout.TimeoutException
                    || t instanceof io.netty.channel.ChannelException
                    || t instanceof ResponseStatusException rse && isGatewayStatus(rse.getStatusCode())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGatewayStatus(HttpStatusCode status) {
        int v = status.value();
        return v == 502 || v == 503 || v == 504;
    }
}
