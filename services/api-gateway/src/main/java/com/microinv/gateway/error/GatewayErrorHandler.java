package com.microinv.gateway.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Errores generados por el propio gateway (sin ruta, destino caido, timeout...) en el formato
 * comun. Va antes que el manejador por defecto de Spring Boot (orden -1). Nunca expone el mensaje
 * de la excepcion; en el log solo el tipo, la ruta y el metodo (jamas cabeceras ni tokens).
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ApiErrorWriter writer;

    public GatewayErrorHandler(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex); // p. ej. cierre de un WebSocket ya establecido: no se puede reescribir
        }
        ErrorMapping.Failure failure = ErrorMapping.classify(ex);
        String where = exchange.getRequest().getMethod() + " " + exchange.getRequest().getPath().value();
        if (failure.status().is5xxServerError()) {
            if (failure.code() == ErrorCode.SERVICE_UNAVAILABLE) {
                log.warn("Upstream failure on {}: {}", where, ex.getClass().getSimpleName());
            } else {
                log.error("Unhandled gateway error on {}: {}", where, ex.getClass().getSimpleName(), ex);
            }
        }
        return writer.write(exchange, failure.status(), failure.code(), failure.code().defaultMessage());
    }
}
