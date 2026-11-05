package com.microinv.inventory.stock;

/** Regla del aviso de stock bajo (contrato 6.2). */
public final class LowStockPolicy {

    private LowStockPolicy() {
    }

    /**
     * Hay aviso solo al CRUZAR el umbral hacia abajo: antes estaba por encima y ahora esta en el umbral o
     * por debajo. Si ya estaba bajo, o el stock sube, no se avisa de nuevo.
     */
    public static boolean crossedBelowThreshold(int stockBefore, int stockAfter, int threshold) {
        return stockBefore > threshold && stockAfter <= threshold;
    }
}
