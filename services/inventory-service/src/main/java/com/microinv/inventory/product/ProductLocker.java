package com.microinv.inventory.product;

import com.microinv.inventory.stock.StockOrdering;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unico punto por el que se bloquean filas de productos. Varias filas SIEMPRE se bloquean de una en una y en
 * orden ascendente de id ({@link StockOrdering}), lo que evita interbloqueos entre transacciones que tocan
 * los mismos productos en distinto orden. Exige transaccion activa (el bloqueo dura hasta el commit).
 */
@Component
public class ProductLocker {

    private final ProductRepository products;

    public ProductLocker(ProductRepository products) {
        this.products = products;
    }

    /** Bloquea un producto (lo que se encuentre; sin filtrar por {@code active}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Product> lock(UUID id) {
        return products.findByIdForUpdate(id);
    }

    /**
     * Bloquea todos los ids pedidos en orden ascendente. El mapa devuelto conserva ese orden y solo contiene
     * los productos que existen.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Product> lockAllAscending(Collection<UUID> ids) {
        Map<UUID, Product> locked = new LinkedHashMap<>();
        for (UUID id : StockOrdering.ascending(ids)) {
            products.findByIdForUpdate(id).ifPresent(p -> locked.put(id, p));
        }
        return locked;
    }
}
