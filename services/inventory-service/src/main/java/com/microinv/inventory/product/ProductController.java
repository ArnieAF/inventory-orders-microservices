package com.microinv.inventory.product;

import com.microinv.inventory.common.PageRequests;
import com.microinv.inventory.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API publica de productos (contrato 6.2). Exige JWT. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    /** Lista blanca de orden (nombre en la API a propiedad de la entidad). */
    static final Map<String, String> SORT_FIELDS = Map.of(
            "name", "name",
            "sku", "sku",
            "price", "price",
            "stock", "stock",
            "createdAt", "createdAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public PageResponse<ProductResponse> list(
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "lowStock", required = false, defaultValue = "false") boolean lowStock,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + PageRequests.DEFAULT_SIZE) int size,
            HttpServletRequest request) {
        // getParameterValues evita que Spring parta "name,asc" por la coma como si fueran dos valores.
        String[] rawSort = request.getParameterValues("sort");
        List<String> sort = rawSort == null ? List.of() : Arrays.asList(rawSort);
        Pageable pageable = PageRequests.parse(page, size, sort, SORT_FIELDS, DEFAULT_SORT, "id");
        return PageResponse.from(productService.list(search, lowStock, pageable), p -> p);
    }

    @GetMapping("/summary")
    public ProductSummary summary() {
        return productService.summary();
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable UUID id) {
        return productService.get(id);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return productService.update(id, request);
    }

    @PostMapping("/{id}/stock-adjustments")
    public ProductResponse adjustStock(@PathVariable UUID id, @Valid @RequestBody StockAdjustmentRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return productService.adjustStock(id, request, actorId(jwt));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** El usuario actual sale SIEMPRE del {@code sub} del token (nunca de cabeceras del cliente). */
    private static UUID actorId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
