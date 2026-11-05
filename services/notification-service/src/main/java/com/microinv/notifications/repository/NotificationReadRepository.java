package com.microinv.notifications.repository;

import com.microinv.notifications.domain.NotificationRead;
import com.microinv.notifications.domain.NotificationReadId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationReadRepository extends JpaRepository<NotificationRead, NotificationReadId> {
}
