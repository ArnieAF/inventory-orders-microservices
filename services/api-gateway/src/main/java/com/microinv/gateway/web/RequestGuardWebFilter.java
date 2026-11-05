package com.microinv.gateway.web;

import com.microinv.gateway.error.ApiErrorWriter;
import com.microinv.gateway.error.ErrorCode;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Primera barrera, ANTES de Spring Security (orden -100) y del enrutado:
 * <ol>
 *   <li>rutas sospechosas o fuera del contrato (p. ej. {@code /internal/**}) -> 404, tengan o no token;</li>
 *   <li>elimina de la peticion entrante las cabeceras de identidad/servicio que un cliente nunca puede
 *       aportar: {@code X-Internal-Key}, {@code X-User-Id}, {@code X-Username}.</li>
 * </ol>
 */
@Component
@Order(RequestGuardWebFilter.ORDER)
public class RequestGuardWebFilter implements WebFilter {

    /** Debe ejecutarse antes que la cadena de Spring Security (que usa -100). */
    public static final int ORDER = -200;

    /** Cabeceras internas que el gateway jamas reenvia desde el exterior. */
    public static final List<String> STRIPPED_HEADERS = List.of("X-Internal-Key", "X-User-Id", "X-Username");

    private final ApiErrorWriter errors;

    public RequestGuardWebFilter(ApiErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String rawPath = request.getURI().getRawPath();
        if (PathPolicy.isSuspicious(rawPath) || !PathPolicy.isAllowed(request.getPath().pathWithinApplication())) {
            return errors.write(exchange, ErrorCode.NOT_FOUND);
        }
        ServerHttpRequest sanitized = request.mutate()
                .headers(headers -> STRIPPED_HEADERS.forEach(headers::remove)) // HttpHeaders: sin distinguir mayusculas
                .build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }
}
