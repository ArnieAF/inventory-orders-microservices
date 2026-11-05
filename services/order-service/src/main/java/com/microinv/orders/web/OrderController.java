package com.microinv.orders.web;

import com.microinv.orders.domain.OrderStatus;
import com.microinv.orders.dto.CreateOrderRequest;
import com.microinv.orders.dto.OrderResponse;
import com.microinv.orders.dto.OrderSummaryResponse;
import com.microinv.orders.dto.OrdersSummaryResponse;
import com.microinv.orders.dto.PageResponse;
import com.microinv.orders.dto.StatusChangeRequest;
import com.microinv.orders.error.ApiException;
import com.microinv.orders.security.CurrentUser;
import com.microinv.orders.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API de pedidos (contrato 6.3). Todo {@code /api/**} exige JWT; el usuario sale de los claims del token. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    /** Lista blanca de campos de orden (nombres de atributo de la entidad). */
    static final Set<String> SORTABLE = Set.of("createdAt", "total", "status", "customerName");

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        OrderResponse created = service.create(request, CurrentUser.from(jwt));
        return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<OrderSummaryResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        OrderStatus filter = parseStatus(status);
        // Valores crudos: Spring partiria "createdAt,desc" por la coma si se declarara como List<String>.
        String[] sort = request.getParameterValues("sort");
        return service.list(filter, PageParams.of(page, size, sort == null ? null : List.of(sort), SORTABLE,
                Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    // Se declara antes que /{id}: el literal "summary" tiene prioridad sobre la variable de ruta.
    @GetMapping("/summary")
    public OrdersSummaryResponse summary() {
        return service.summary();
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PatchMapping("/{id}/status")
    public OrderResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusChangeRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        return service.changeStatus(id, request.status(), CurrentUser.from(jwt));
    }

    private static OrderStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("status", "must be one of PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED");
        }
    }
}
