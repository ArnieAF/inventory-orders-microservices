package com.microinv.orders.repository;

import com.microinv.orders.domain.OrderEntity;
import com.microinv.orders.domain.OrderStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findWithItemsById(UUID id);

    Page<OrderEntity> findByStatus(OrderStatus status, Pageable pageable);

    @Query("select o.id as id, size(o.items) as itemCount from OrderEntity o where o.id in :ids")
    List<ItemCount> countItems(@Param("ids") Collection<UUID> ids);

    @Query("select o.status as status, count(o) as total from OrderEntity o group by o.status")
    List<StatusCount> countByStatus();

    interface ItemCount {
        UUID getId();

        Number getItemCount();
    }

    interface StatusCount {
        OrderStatus getStatus();

        Number getTotal();
    }
}
