package com.microinv.orders.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    private UUID id;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(length = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_by_username", nullable = false, updatable = false, length = 64)
    private String createdByUsername;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Bloqueo optimista: dos cambios de estado concurrentes sobre el mismo pedido no pueden ganar ambos. */
    @Version
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    private List<OrderItemEntity> items = new ArrayList<>();

    protected OrderEntity() {
        // JPA
    }

    /** Crea un pedido PENDING; el total es la suma de las lineas (BigDecimal, 2 decimales). */
    public static OrderEntity create(UUID id, String customerName, String notes, UUID createdBy,
                                     String createdByUsername, List<OrderLine> lines, Instant now) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        OrderEntity order = new OrderEntity();
        order.id = id;
        order.customerName = customerName;
        order.notes = notes;
        order.status = OrderStatus.PENDING;
        order.createdBy = createdBy;
        order.createdByUsername = createdByUsername;
        order.createdAt = now;
        order.updatedAt = now;

        BigDecimal total = BigDecimal.ZERO.setScale(OrderItemEntity.MONEY_SCALE);
        for (OrderLine line : lines) {
            OrderItemEntity item = new OrderItemEntity(order, line);
            order.items.add(item);
            total = total.add(item.getLineTotal());
        }
        order.total = total;
        return order;
    }

    /** Aplica una transicion valida; lanza {@link IllegalStateException} si el contrato no la permite. */
    public void changeStatus(OrderStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid status transition");
        }
        this.status = target;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getNotes() {
        return notes;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public String getCreatedByUsername() {
        return createdByUsername;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public List<OrderItemEntity> getItems() {
        return Collections.unmodifiableList(items);
    }
}
