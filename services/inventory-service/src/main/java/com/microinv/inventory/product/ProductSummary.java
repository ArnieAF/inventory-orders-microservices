package com.microinv.inventory.product;

/** {@code GET /api/products/summary}: solo cuenta productos activos. */
public record ProductSummary(long totalProducts, long lowStockCount, long totalUnits) {
}
