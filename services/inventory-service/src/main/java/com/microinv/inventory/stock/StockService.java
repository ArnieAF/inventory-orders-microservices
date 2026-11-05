package com.microinv.inventory.stock;

import com.microinv.inventory.error.ApiException;
import com.microinv.inventory.error.ApiException.StockShortage;
import com.microinv.inventory.error.ErrorCode;
import com.microinv.inventory.product.Product;
import com.microinv.inventory.product.ProductLocker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserva y liberacion de stock por pedido (contrato 6.2).
 * <ul>
 *   <li>Todo o nada: una sola transaccion; si algo falla, no se descuenta nada.</li>
 *   <li>Las filas de producto se bloquean ANTES de leer/decidir y siempre en orden ascendente de id
 *       ({@link ProductLocker}), asi que no hay leer-modificar-escribir sin proteccion ni interbloqueos.</li>
 *   <li>Idempotente por {@code orderRef}: la comprobacion de movimientos previos se hace DESPUES de
 *       bloquear, de modo que dos peticiones simultaneas con el mismo {@code orderRef} se serializan y la
 *       segunda ve el resultado de la primera.</li>
 * </ul>
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final ProductLocker locker;
    private final StockMovementRepository movements;
    private final ApplicationEventPublisher events;

    public StockService(ProductLocker locker, StockMovementRepository movements, ApplicationEventPublisher events) {
        this.locker = locker;
        this.movements = movements;
        this.events = events;
    }

    @Transactional
    public ReserveResponse reserve(UUID orderRef, List<ReserveRequest.Item> items) {
        Map<UUID, Integer> requested = toRequestedMap(items);

        // 1. Bloqueo de todas las filas, en orden ascendente de productId.
        Map<UUID, Product> locked = locker.lockAllAscending(requested.keySet());

        // 2. Idempotencia (ya con las filas bloqueadas).
        List<StockMovement> existing = movements.findByOrderRef(orderRef);
        if (!existing.isEmpty()) {
            return replay(existing, requested, locked);
        }

        // 3. Validacion todo-o-nada: existencia, producto activo y stock suficiente.
        for (UUID productId : requested.keySet()) {
            if (!locked.containsKey(productId)) {
                throw ApiException.notFound("Product not found");
            }
        }
        for (UUID productId : requested.keySet()) {
            if (!locked.get(productId).isActive()) {
                throw ApiException.productInactive(productId);
            }
        }
        List<StockShortage> shortages = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : requested.entrySet()) {
            int available = locked.get(entry.getKey()).getStock();
            if (available < entry.getValue()) {
                shortages.add(new StockShortage(entry.getKey(), entry.getValue(), available));
            }
        }
        if (!shortages.isEmpty()) {
            throw ApiException.insufficientStock(shortages);
        }

        // 4. Aplicacion (ya nada puede fallar por stock): descuento + movimiento, en orden ascendente.
        List<ReserveResponse.Item> result = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : requested.entrySet()) {
            Product product = locked.get(entry.getKey());
            int quantity = entry.getValue();
            int before = product.getStock();
            product.changeStock(before - quantity);
            movements.save(StockMovement.reserve(product.getId(), quantity, orderRef));
            if (LowStockPolicy.crossedBelowThreshold(before, product.getStock(), product.getLowStockThreshold())) {
                events.publishEvent(new LowStockEvent(product.getId(), product.getSku(), product.getName(),
                        product.getStock(), product.getLowStockThreshold(), null));
            }
            result.add(toItem(product, quantity));
        }
        movements.flush();
        log.debug("Stock reserved for orderRef={} ({} lines)", orderRef, result.size());
        return new ReserveResponse(result);
    }

    @Transactional
    public void release(UUID orderRef) {
        // Lectura previa solo para saber que productos bloquear.
        Set<UUID> reservedProducts = new HashSet<>();
        for (StockMovement m : movements.findByOrderRef(orderRef)) {
            if (m.getType() == MovementType.RESERVE) {
                reservedProducts.add(m.getProductId());
            }
        }
        if (reservedProducts.isEmpty()) {
            return; // nunca reservado: no-op
        }

        // Bloqueo en orden ascendente (incluye productos dados de baja) y relectura ya con el bloqueo.
        Map<UUID, Product> locked = new LinkedHashMap<>(locker.lockAllAscending(reservedProducts));
        List<StockMovement> current = movements.findByOrderRef(orderRef);

        Set<UUID> alreadyReleased = new HashSet<>();
        List<StockMovement> toRevert = new ArrayList<>();
        for (StockMovement m : current) {
            if (m.getType() == MovementType.RELEASE) {
                alreadyReleased.add(m.getProductId());
            } else if (m.getType() == MovementType.RESERVE) {
                toRevert.add(m);
            }
        }
        toRevert.removeIf(m -> alreadyReleased.contains(m.getProductId()));
        if (toRevert.isEmpty()) {
            return; // ya liberado: no-op
        }
        toRevert.sort((a, b) -> StockOrdering.ASCENDING.compare(a.getProductId(), b.getProductId()));

        Set<UUID> missing = new HashSet<>();
        for (StockMovement m : toRevert) {
            if (!locked.containsKey(m.getProductId())) {
                missing.add(m.getProductId());
            }
        }
        if (!missing.isEmpty()) {
            locked.putAll(locker.lockAllAscending(missing)); // solo ocurre si otra peticion modifico el pedido
        }

        for (StockMovement reserved : toRevert) {
            Product product = locked.get(reserved.getProductId());
            if (product == null) {
                throw new IllegalStateException("Reserved product no longer exists"); // imposible por la FK
            }
            int quantity = -reserved.getDelta();
            product.changeStock(Math.addExact(product.getStock(), quantity));
            movements.save(StockMovement.release(product.getId(), quantity, orderRef));
        }
        movements.flush();
        log.debug("Stock released for orderRef={} ({} lines)", orderRef, toRevert.size());
    }

    /** Valida las lineas y las ordena por productId ascendente. Rechaza productId repetidos y cantidades &lt; 1. */
    private static Map<UUID, Integer> toRequestedMap(List<ReserveRequest.Item> items) {
        if (items == null || items.isEmpty()) {
            throw ApiException.validation("items", "must contain at least one item");
        }
        Map<UUID, Integer> requested = new TreeMap<>(StockOrdering.ASCENDING);
        for (ReserveRequest.Item item : items) {
            if (item == null || item.productId() == null || item.quantity() == null) {
                throw ApiException.validation("items", "productId and quantity are required");
            }
            if (item.quantity() < 1) {
                throw ApiException.validation("items", "quantity must be at least 1");
            }
            if (requested.put(item.productId(), item.quantity()) != null) {
                throw ApiException.validation("items", "productId must not be repeated");
            }
        }
        return requested;
    }

    /** Reintento del mismo orderRef: devuelve el resultado original sin volver a descontar. */
    private ReserveResponse replay(List<StockMovement> existing, Map<UUID, Integer> requested,
                                   Map<UUID, Product> locked) {
        Map<UUID, Integer> reserved = new TreeMap<>(StockOrdering.ASCENDING);
        boolean released = false;
        for (StockMovement m : existing) {
            if (m.getType() == MovementType.RESERVE) {
                reserved.put(m.getProductId(), -m.getDelta());
            } else if (m.getType() == MovementType.RELEASE) {
                released = true;
            }
        }
        if (reserved.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION, "Order reference cannot be reserved", null);
        }
        if (released) {
            // La reserva original ya se revirtio: devolver "exito" dejaria al llamante con stock que no tiene.
            throw ApiException.invalidStatusTransition("Order reference was already released");
        }
        if (!reserved.equals(requested)) {
            throw ApiException.validation("items", "orderRef was already reserved with different items");
        }
        List<ReserveResponse.Item> result = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : reserved.entrySet()) {
            result.add(toItem(locked.get(entry.getKey()), entry.getValue()));
        }
        return new ReserveResponse(result);
    }

    private static ReserveResponse.Item toItem(Product product, int quantity) {
        return new ReserveResponse.Item(product.getId(), product.getSku(), product.getName(), product.getPrice(),
                quantity);
    }
}
