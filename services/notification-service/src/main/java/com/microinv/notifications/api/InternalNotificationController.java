package com.microinv.notifications.api;

import com.microinv.notifications.api.dto.CreateNotificationRequest;
import com.microinv.notifications.api.dto.NotificationResponse;
import com.microinv.notifications.service.NewNotification;
import com.microinv.notifications.service.NotificationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada de notificaciones desde otros servicios (inventario, pedidos). Protegida por {@code X-Internal-Key}
 * (ver {@code InternalApiKeyFilter}); no acepta JWT y el gateway nunca la enruta.
 */
@RestController
@RequestMapping("/internal/notifications")
public class InternalNotificationController {

    private final NotificationService service;

    public InternalNotificationController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationResponse create(@Valid @RequestBody CreateNotificationRequest request) {
        return service.create(new NewNotification(
                request.type(),
                request.title(),
                request.message(),
                parseUuid(request.targetUserId()),
                parseUuid(request.actorUserId()),
                request.entityType(),
                parseUuid(request.entityId())));
    }

    private static UUID parseUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
