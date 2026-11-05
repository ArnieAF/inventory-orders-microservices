package com.microinv.inventory.stock;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Orden unico y estable para bloquear filas (ascendente de {@code productId}). Se compara por la
 * representacion textual en minusculas, que coincide con el orden de bytes sin signo que usa PostgreSQL
 * para el tipo {@code uuid}; {@link UUID#compareTo(UUID)} compara con signo y daria un orden distinto.
 * Lo importante es que TODAS las rutas que bloquean varias filas usen este mismo orden.
 */
public final class StockOrdering {

    public static final Comparator<UUID> ASCENDING = Comparator.comparing(UUID::toString);

    private StockOrdering() {
    }

    public static List<UUID> ascending(Collection<UUID> ids) {
        return ids.stream().distinct().sorted(ASCENDING).toList();
    }
}
