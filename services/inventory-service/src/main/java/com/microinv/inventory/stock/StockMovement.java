package com.microinv.inventory.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Registro inmutable de un cambio de stock. {@code delta} es negativo en RESERVE y positivo en RELEASE;
 * en ADJUSTMENT lleva el signo del ajuste. {@code orderRef} es nulo en los ajustes manuales.
 * El unico {@code (order_ref, product_id, type)} de la BD garantiza la idempotencia por pedido.
 */
@Entity
@Table(name = "stock_movements")
public class StockMovement implements Persistable<UUID> {

    static final String REASON_RESERVE = "Order reservation";
    static final String REASON_RELEASE = "Order release";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "delta", nullable = false, updatable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, updatable = false, length = 20)
    private MovementType type;

    @Column(name = "reason", updatable = false, length = 200)
    private String reason;

    @Column(name = "order_ref", updatable = false)
    private UUID orderRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected StockMovement() {
        // JPA
    }

    private StockMovement(UUID productId, int delta, MovementType type, String reason, UUID orderRef) {
        this.id = UUID.randomUUID();
        this.productId = productId;
        this.delta = delta;
        this.type = type;
        this.reason = reason;
        this.orderRef = orderRef;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static StockMovement reserve(UUID productId, int quantity, UUID orderRef) {
        return new StockMovement(productId, -quantity, MovementType.RESERVE, REASON_RESERVE, orderRef);
    }

    public static StockMovement release(UUID productId, int quantity, UUID orderRef) {
        return new StockMovement(productId, quantity, MovementType.RELEASE, REASON_RELEASE, orderRef);
    }

    public static StockMovement adjustment(UUID productId, int delta, String reason) {
        return new StockMovement(productId, delta, MovementType.ADJUSTMENT, reason, null);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getDelta() {
        return delta;
    }

    public MovementType getType() {
        return type;
    }

    public String getReason() {
        return reason;
    }

    public UUID getOrderRef() {
        return orderRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
