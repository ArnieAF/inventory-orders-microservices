package com.microinv.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Instantanea inmutable de un producto dentro de un pedido: el historial no cambia si el producto cambia. */
@Entity
@Table(name = "order_items")
public class OrderItemEntity {

    static final int MONEY_SCALE = 2;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderEntity order;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false, length = 40)
    private String sku;

    @Column(name = "product_name", nullable = false, updatable = false, length = 120)
    private String productName;

    @Column(name = "unit_price", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    protected OrderItemEntity() {
        // JPA
    }

    OrderItemEntity(OrderEntity order, OrderLine line) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.productId = line.productId();
        this.sku = line.sku();
        this.productName = line.productName();
        this.unitPrice = line.unitPrice().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        this.quantity = line.quantity();
        this.lineTotal = this.unitPrice.multiply(BigDecimal.valueOf(line.quantity()))
                .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getSku() {
        return sku;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
