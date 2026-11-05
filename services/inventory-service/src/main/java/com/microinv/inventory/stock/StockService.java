package com.microinv.inventory.stock;

import com.microinv.inventory.error.ApiException;
import com.microinv.inventory.error.ApiException.StockShortage;
import com.microinv.inventory.error.ErrorCode;
import com.microinv.inventory.product.Product;
import com.microinv.inventory.product.ProductLocker;
import com.microinv.inventory.product.ProductRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reserva y liberacion de stock por pedido (contrato 6.2).
 * <ul>
 *   <li><b>Todo o nada</b>: una sola transaccion; si algo falla, no se descuenta nada.</li>
 *   <li><b>Cabecera por orderRef</b> ({@code stock_reservations}): reserve y release bloquean/crean la cabecera
 *       ANTES de bloquear productos. La PK serializa las peticiones del mismo orderRef aunque los productos
 *       sean distintos, y un release que llega antes que la reserva deja una lapida RELEASED: la reserva tardia
 *       recibe 409 en lugar de descontar stock para un pedido ya compensado.</li>
 *   <li><b>Orden de bloqueo fijo</b>: primero la cabecera, despues los productos en orden ascendente de id
 *       ({@link ProductLocker}); asi no hay interbloqueos ni leer-modificar-escribir sin proteccion.</li>
 *   <li><b>Idempotente</b>: reservar dos veces el mismo orderRef devuelve el resultado original; liberar dos
 *       veces es no-op.</li>
 *   <li><b>Timeout de 4 s</b> (menor que los 5 s de lectura de order-service) en la transaccion; en PostgreSQL
 *       ademas {@code lock_timeout} de 4 s por conexion. Si se agotan: 503 con {@code Retry-After} y nada
 *       aplicado, para que inventario falle ANTES de que order-service compense a ciegas.</li>
 * </ul>
 * El INSERT concurrente de la cabecera del mismo orderRef choca con la PK: en PostgreSQL eso aborta la
 * transaccion, asi que se reintenta la operacion entera en una transaccion nueva (que ya ve la cabecera
 * confirmada por la ganadora y acaba en replay/serializacion), nunca en un 500.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    /** Timeout total de reserve/release: menor que la lectura de 5 s del llamante. */
    static final int TRANSACTION_TIMEOUT_SECONDS = 4;
    private static final long RETRY_BUDGET_MILLIS = 3000;
    private static final int MAX_ATTEMPTS = 3;

    private final ProductLocker locker;
    private final ProductRepository products;
    private final StockMovementRepository movements;
    private final StockReservationRepository reservations;
    private final ApplicationEventPublisher events;
    private final PlatformTransactionManager transactionManager;

    public StockService(ProductLocker locker, ProductRepository products, StockMovementRepository movements,
                        StockReservationRepository reservations, ApplicationEventPublisher events,
                        PlatformTransactionManager transactionManager) {
        this.locker = locker;
        this.products = products;
        this.movements = movements;
        this.reservations = reservations;
        this.events = events;
        this.transactionManager = transactionManager;
    }

    public ReserveResponse reserve(UUID orderRef, List<ReserveRequest.Item> items) {
        Map<UUID, Integer> requested = toRequestedMap(items); // valida antes de abrir transaccion
        return inTransaction(() -> doReserve(orderRef, requested));
    }

    public void release(UUID orderRef) {
        inTransaction(() -> {
            doRelease(orderRef);
            return null;
        });
    }

    // ---------- transaccion con timeout y reintento ante choque de PK ----------

    private <T> T inTransaction(Supplier<T> work) {
        long start = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setTimeout(Math.max(1, TRANSACTION_TIMEOUT_SECONDS - (int) (elapsedMillis / 1000)));
            try {
                return template.execute(status -> work.get());
            } catch (DataIntegrityViolationException e) {
                // Otra transaccion creo la cabecera del mismo orderRef entre nuestro SELECT y nuestro INSERT.
                // La transaccion esta abortada: se repite entera y ahora ve la cabecera ya confirmada.
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                if (elapsed >= RETRY_BUDGET_MILLIS) {
                    log.warn("Header insert conflict and no time left to retry ({} ms)", elapsed);
                    throw new CannotAcquireLockException("Order reference is busy", e);
                }
                log.debug("Reservation header insert conflict, retrying (attempt {})", attempt + 1);
            }
        }
    }

    // ---------- reserve ----------

    private ReserveResponse doReserve(UUID orderRef, Map<UUID, Integer> requested) {
        // 1. Cabecera: bloquearla si existe, crearla si no (el INSERT espera a una ganadora concurrente).
        StockReservation header = reservations.findByOrderRefForUpdate(orderRef).orElse(null);
        if (header != null) {
            if (header.getStatus() == ReservationStatus.RELEASED) {
                // Liberada (o lapida de un release anterior): jamas se vuelve a descontar para este orderRef.
                throw ApiException.invalidStatusTransition("Order reference was already released");
            }
            return replay(orderRef, requested);
        }
        reservations.saveAndFlush(StockReservation.reserved(orderRef));

        // 2. Bloqueo de todas las filas de producto, en orden ascendente de productId.
        Map<UUID, Product> locked = locker.lockAllAscending(requested.keySet());

        // 3. Validacion todo-o-nada: existencia, producto activo y stock suficiente (se informan TODOS).
        List<UUID> missing = requested.keySet().stream().filter(id -> !locked.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.productsNotFound(missing);
        }
        List<UUID> inactive = requested.keySet().stream().filter(id -> !locked.get(id).isActive()).toList();
        if (!inactive.isEmpty()) {
            throw ApiException.productsInactive(inactive);
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

    /** Reintento del mismo orderRef (cabecera RESERVED): devuelve el resultado original sin volver a descontar. */
    private ReserveResponse replay(UUID orderRef, Map<UUID, Integer> requested) {
        Map<UUID, Integer> reserved = new TreeMap<>(StockOrdering.ASCENDING);
        for (StockMovement m : movements.findByOrderRef(orderRef)) {
            if (m.getType() == MovementType.RESERVE) {
                reserved.put(m.getProductId(), -m.getDelta());
            }
        }
        if (reserved.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION, "Order reference cannot be reserved", null);
        }
        if (!reserved.equals(requested)) {
            throw ApiException.validation("items", "orderRef was already reserved with different items");
        }
        Map<UUID, Product> byId = new HashMap<>();
        products.findAllById(reserved.keySet()).forEach(p -> byId.put(p.getId(), p));
        List<ReserveResponse.Item> result = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : reserved.entrySet()) {
            result.add(toItem(byId.get(entry.getKey()), entry.getValue()));
        }
        return new ReserveResponse(result);
    }

    // ---------- release ----------

    private void doRelease(UUID orderRef) {
        StockReservation header = reservations.findByOrderRefForUpdate(orderRef).orElse(null);
        if (header == null) {
            // Nunca reservado (o la reserva aun no confirmo): lapida, para que una reserva tardia no descuente.
            reservations.saveAndFlush(StockReservation.tombstone(orderRef));
            return;
        }
        if (header.getStatus() == ReservationStatus.RELEASED) {
            return; // ya liberado: no-op
        }

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
        toRevert.sort((a, b) -> StockOrdering.ASCENDING.compare(a.getProductId(), b.getProductId()));

        // Bloqueo en orden ascendente (incluye productos dados de baja).
        Map<UUID, Product> locked = locker.lockAllAscending(toRevert.stream().map(StockMovement::getProductId).toList());
        for (StockMovement reserved : toRevert) {
            Product product = locked.get(reserved.getProductId());
            if (product == null) {
                throw new IllegalStateException("Reserved product no longer exists"); // imposible por la FK
            }
            int quantity = -reserved.getDelta();
            // stock <= 1e9 y cantidad reservada <= stock original <= 1e9: la suma cabe siempre en un int. Se
            // calcula con long y se acota por si algun dato antiguo lo violara: liberar NUNCA debe fallar.
            long restored = (long) product.getStock() + quantity;
            if (restored > Integer.MAX_VALUE) {
                log.error("Released stock for productId={} exceeds the int range; clamping", product.getId());
                restored = Integer.MAX_VALUE;
            }
            product.changeStock((int) restored);
            movements.save(StockMovement.release(product.getId(), quantity, orderRef));
        }
        header.markReleased();
        reservations.flush();
        log.debug("Stock released for orderRef={} ({} lines)", orderRef, toRevert.size());
    }

    // ---------- utilidades ----------

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

    private static ReserveResponse.Item toItem(Product product, int quantity) {
        return new ReserveResponse.Item(product.getId(), product.getSku(), product.getName(), product.getPrice(),
                quantity);
    }
}
