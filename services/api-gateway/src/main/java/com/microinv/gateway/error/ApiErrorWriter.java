package com.microinv.gateway.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Escribe el formato comun de error en una respuesta reactiva. */
@Component
public class ApiErrorWriter {

    private static final byte[] FALLBACK = ("{\"status\":500,\"code\":\"INTERNAL_ERROR\","
            + "\"message\":\"Internal server error\"}").getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code) {
        return write(exchange, code.status(), code, code.defaultMessage());
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, ErrorCode code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(
                    ApiError.of(status.value(), code.name(), message, exchange.getRequest().getPath().value()));
        } catch (JsonProcessingException e) {
            bytes = FALLBACK;
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
