package com.microinv.orders.client;

import com.microinv.orders.error.InventoryRejectedException;
import com.microinv.orders.error.InventoryUnavailableException;
import java.util.List;
import java.util.UUID;

/**
 * Puerto hacia inventory-service (rutas internas del contrato 6.2). Existe como interfaz para poder
 * simularlo en las pruebas.
 */
public interface InventoryClient {

    /**
     * Reserva stock de forma atomica e idempotente por {@code orderRef}.
     *
     * @throws InventoryRejectedException    409 INSUFFICIENT_STOCK / 409 PRODUCT_INACTIVE / 404 NOT_FOUND
     * @throws InventoryUnavailableException cualquier otro fallo, timeout o 5xx
     */
    ReserveResult reserve(UUID orderRef, List<ReserveItem> items);

    /**
     * Libera lo reservado para {@code orderRef} (idempotente: liberar dos veces o algo nunca reservado es no-op).
     *
     * @throws InventoryUnavailableException si la liberacion no se pudo confirmar
     */
    void release(UUID orderRef);
}
