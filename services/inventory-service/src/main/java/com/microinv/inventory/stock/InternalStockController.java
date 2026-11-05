package com.microinv.inventory.stock;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints servicio a servicio (contrato 6.2). Protegidos por {@code X-Internal-Key}; no aceptan JWT. */
@RestController
@RequestMapping("/internal/stock")
public class InternalStockController {

    private final StockService stockService;

    public InternalStockController(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("/reserve")
    public ReserveResponse reserve(@Valid @RequestBody ReserveRequest request) {
        return stockService.reserve(request.orderRef(), request.items());
    }

    @PostMapping("/release")
    public ResponseEntity<Void> release(@Valid @RequestBody ReleaseRequest request) {
        stockService.release(request.orderRef());
        return ResponseEntity.noContent().build();
    }
}
