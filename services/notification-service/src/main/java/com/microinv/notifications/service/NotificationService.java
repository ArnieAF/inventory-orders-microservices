package com.microinv.notifications.service;

import com.microinv.notifications.api.ApiException;
import com.microinv.notifications.api.dto.NotificationResponse;
import com.microinv.notifications.api.dto.PageResponse;
import com.microinv.notifications.domain.Notification;
import com.microinv.notifications.domain.NotificationRead;
import com.microinv.notifications.domain.NotificationReadId;
import com.microinv.notifications.repository.NotificationReadRepository;
import com.microinv.notifications.repository.NotificationRepository;
import com.microinv.notifications.repository.NotificationView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lógica de notificaciones: creación (persistir y DESPUÉS del commit emitir en tiempo real) e historial/lectura
 * por usuario.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int READ_ALL_BATCH_SIZE = 500;
    private static final int MAX_CONFLICT_RETRIES = 3;

    private final NotificationRepository notifications;
    private final NotificationReadRepository reads;
    private final NotificationPublisher publisher;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public NotificationService(NotificationRepository notifications, NotificationReadRepository reads,
                               NotificationPublisher publisher, PlatformTransactionManager transactionManager,
                               Clock clock) {
        this.notifications = notifications;
        this.reads = reads;
        this.publisher = publisher;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Persiste la notificación y, una vez confirmada la transacción, la emite por WebSocket. Un fallo al emitir NO
     * se propaga (la notificación ya está guardada y es consultable por REST): se registra.
     */
    public NotificationResponse create(NewNotification command) {
        Notification notification = new Notification(UUID.randomUUID(), command.type(), command.title(),
                command.message(), command.targetUserId(), command.actorUserId(), command.entityType(),
                command.entityId(), clock.instant().truncatedTo(ChronoUnit.MILLIS));
        transactions.executeWithoutResult(status -> entityManager.persist(notification));

        // La transacción ya está confirmada aquí: emitir es posterior al commit.
        NotificationResponse response = NotificationResponse.from(notification, false);
        try {
            publisher.publish(response);
        } catch (RuntimeException ex) {
            log.error("Notification {} was saved but could not be emitted over WebSocket", notification.getId(), ex);
        }
        return response;
    }

    /** Historial visible para el usuario (difusión + dirigidas a él), con "leída" calculado por usuario. */
    public PageResponse<NotificationResponse> list(UUID userId, boolean unreadOnly, Pageable pageable) {
        Page<NotificationView> page = unreadOnly
                ? notifications.findVisibleUnread(userId, pageable)
                : notifications.findVisible(userId, pageable);
        return PageResponse.of(page.map(NotificationResponse::from));
    }

    public long countUnread(UUID userId) {
        return notifications.countVisibleUnread(userId);
    }

    /**
     * Marca como leída una notificación visible para el usuario. Idempotente. Si no existe o no le es visible
     * (dirigida a otro usuario) responde 404, sin distinguir los dos casos.
     */
    public void markRead(UUID userId, UUID notificationId) {
        if (notifications.countVisibleById(notificationId, userId) == 0) {
            throw ApiException.notFound();
        }
        try {
            transactions.executeWithoutResult(status -> {
                if (!reads.existsById(new NotificationReadId(notificationId, userId))) {
                    entityManager.persist(new NotificationRead(notificationId, userId, clock.instant()));
                }
            });
        } catch (RuntimeException ex) {
            // Otra petición concurrente la marcó entre la comprobación y el insert: el resultado es el buscado.
            if (!isIntegrityViolation(ex)) {
                throw ex;
            }
        }
    }

    /** Marca como leídas solo las notificaciones visibles para el usuario (nunca las dirigidas a otros). */
    public void markAllRead(UUID userId) {
        for (int attempt = 1; ; attempt++) {
            try {
                transactions.executeWithoutResult(status -> markAllReadInTransaction(userId));
                return;
            } catch (RuntimeException ex) {
                // Una escritura concurrente puede chocar con la clave primaria: reintentar recalcula lo pendiente.
                if (!isIntegrityViolation(ex) || attempt >= MAX_CONFLICT_RETRIES) {
                    throw ex;
                }
            }
        }
    }

    private void markAllReadInTransaction(UUID userId) {
        Instant now = clock.instant();
        List<UUID> pending;
        do {
            pending = notifications.findVisibleUnreadIds(userId, PageRequest.of(0, READ_ALL_BATCH_SIZE));
            for (UUID id : pending) {
                entityManager.persist(new NotificationRead(id, userId, now));
            }
            entityManager.flush();
            entityManager.clear();
        } while (pending.size() == READ_ALL_BATCH_SIZE);
    }

    /** {@code true} si la excepción (o su causa) es una violación de integridad (SQLSTATE clase 23). */
    static boolean isIntegrityViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof DataIntegrityViolationException
                    || current instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            if (current instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
