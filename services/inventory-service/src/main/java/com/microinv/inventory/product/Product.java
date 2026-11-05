package com.microinv.inventory.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Producto. El stock solo cambia mediante {@link #changeStock(int)} y siempre con la fila bloqueada
 * (ver {@link ProductLocker}); {@code @Version} es una segunda barrera contra escrituras perdidas.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sku", nullable = false, length = 40)
    private String sku;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "price", nullable = false, precision = 8, scale = 2)
    private BigDecimal price;

    @Column(name = "stock", nullable = false)
    private int stock;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Product() {
        // JPA
    }

    public static Product create(String sku, String name, String description, BigDecimal price, int stock,
                                 int lowStockThreshold) {
        Product p = new Product();
        p.id = UUID.randomUUID();
        p.sku = sku;
        p.name = name;
        p.description = description;
        p.price = price.setScale(2, RoundingMode.UNNECESSARY);
        p.stock = stock;
        p.lowStockThreshold = lowStockThreshold;
        p.active = true;
        return p;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Actualiza los datos editables (el stock NO se cambia aqui). */
    public void updateDetails(String sku, String name, String description, BigDecimal price, int lowStockThreshold) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price.setScale(2, RoundingMode.UNNECESSARY);
        this.lowStockThreshold = lowStockThreshold;
    }

    /** Fija el nuevo stock; el invariante {@code stock >= 0} se comprueba tambien aqui. */
    public void changeStock(int newStock) {
        if (newStock < 0) {
            throw new IllegalArgumentException("stock cannot be negative");
        }
        this.stock = newStock;
    }

    public void deactivate() {
        this.active = false;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public int getLowStockThreshold() {
        return lowStockThreshold;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
