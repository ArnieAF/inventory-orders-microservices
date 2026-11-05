package com.microinv.inventory.stock;

/**
 * Limites numericos del contrato (6.2): evitan desbordes de {@code int}. Con stock <= 1e9 y |delta| <= 1e9,
 * {@code stock + delta} nunca supera 2e9 (< 2^31-1) y {@code -delta} siempre cabe en un {@code int}.
 */
public final class StockLimits {

    public static final int MAX_STOCK = 1_000_000_000;
    public static final int MAX_ABS_DELTA = 1_000_000_000;

    private StockLimits() {
    }
}
