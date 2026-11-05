package com.microinv.inventory.product;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

    /** Lectura con bloqueo pesimista de escritura (SELECT ... FOR UPDATE). Usar siempre via {@link ProductLocker}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    long countByActiveTrue();

    /** Stock bajo = {@code stock <= low_stock_threshold} (mismo criterio que el aviso de cruce de umbral). */
    @Query("select count(p) from Product p where p.active = true and p.stock <= p.lowStockThreshold")
    long countLowStock();

    @Query("select coalesce(sum(p.stock), 0L) from Product p where p.active = true")
    long sumActiveStock();
}
