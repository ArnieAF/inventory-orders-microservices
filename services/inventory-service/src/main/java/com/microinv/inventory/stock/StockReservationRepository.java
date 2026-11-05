package com.microinv.inventory.stock;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    /** Lectura con bloqueo de la cabecera (SELECT ... FOR UPDATE). Vacio si aun no existe ninguna confirmada. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservation r where r.orderRef = :orderRef")
    Optional<StockReservation> findByOrderRefForUpdate(@Param("orderRef") UUID orderRef);
}
