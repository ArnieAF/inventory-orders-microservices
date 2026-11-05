package com.microinv.inventory.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code Product} del contrato 6.2 (sin el campo interno {@code version}). */
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        int stock,
        int lowStockThreshold,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(),
                p.getStock(), p.getLowStockThreshold(), p.isActive(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
