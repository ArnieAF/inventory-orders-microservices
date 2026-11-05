package com.microinv.notifications.repository;

import com.microinv.notifications.domain.Notification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Consultas de historial. Regla de visibilidad (§6.4): una notificación es visible para el usuario si
 * {@code target_user_id IS NULL} (difusión) o {@code target_user_id = userId}. El estado "leída" se calcula
 * por usuario con {@code notification_reads}.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    String VIEW = "select new com.microinv.notifications.repository.NotificationView("
            + "n.id, n.type, n.title, n.message, n.targetUserId, n.actorUserId, n.entityType, n.entityId, "
            + "n.createdAt, case when r.id.userId is null then false else true end) ";

    String FROM_WITH_READS = "from Notification n left join NotificationRead r "
            + "on r.id.notificationId = n.id and r.id.userId = :userId ";

    String VISIBLE = "(n.targetUserId is null or n.targetUserId = :userId)";

    String NOT_READ = "not exists (select 1 from NotificationRead rr "
            + "where rr.id.notificationId = n.id and rr.id.userId = :userId)";

    @Query(value = VIEW + FROM_WITH_READS + "where " + VISIBLE,
            countQuery = "select count(n) from Notification n where " + VISIBLE)
    Page<NotificationView> findVisible(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = VIEW + FROM_WITH_READS + "where " + VISIBLE + " and r.id.userId is null",
            countQuery = "select count(n) from Notification n where " + VISIBLE + " and " + NOT_READ)
    Page<NotificationView> findVisibleUnread(@Param("userId") UUID userId, Pageable pageable);

    @Query("select count(n) from Notification n where " + VISIBLE + " and " + NOT_READ)
    long countVisibleUnread(@Param("userId") UUID userId);

    @Query("select count(n) from Notification n where n.id = :id and " + VISIBLE)
    long countVisibleById(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Identificadores visibles y aún sin leer para el usuario, del más antiguo al más reciente. */
    @Query("select n.id from Notification n where " + VISIBLE + " and " + NOT_READ + " order by n.createdAt, n.id")
    List<UUID> findVisibleUnreadIds(@Param("userId") UUID userId, Pageable pageable);
}
