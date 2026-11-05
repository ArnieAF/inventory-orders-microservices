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
 * Cabecera por {@code orderRef}. Siempre se lee con bloqueo ({@link StockReservationRepository}) y se crea
 * antes de bloquear productos: la PK serializa las peticiones del mismo pedido. Estado RELEASED sin
 * movimientos = lapida (un release llego antes que la reserva).
 */
@Entity
@Table(name = "stock_reservations")
public class StockReservation implements Persistable<UUID> {

    @Id
    @Column(name = "order_ref", nullable = false, updatable = false)
    private UUID orderRef;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected StockReservation() {
        // JPA
    }

    private StockReservation(UUID orderRef, ReservationStatus status) {
        this.orderRef = orderRef;
        this.status = status;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static StockReservation reserved(UUID orderRef) {
        return new StockReservation(orderRef, ReservationStatus.RESERVED);
    }

    public static StockReservation tombstone(UUID orderRef) {
        return new StockReservation(orderRef, ReservationStatus.RELEASED);
    }

    public void markReleased() {
        this.status = ReservationStatus.RELEASED;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public UUID getId() {
        return orderRef;
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
}
