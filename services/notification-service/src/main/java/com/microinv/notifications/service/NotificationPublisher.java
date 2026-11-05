package com.microinv.notifications.service;

import com.microinv.notifications.api.dto.NotificationResponse;

/** Emite una notificación ya persistida a los clientes conectados (en tiempo real). */
public interface NotificationPublisher {

    /**
     * Difusión ({@code targetUserId == null}) o entrega dirigida al usuario destino. Puede lanzar una excepción en
     * tiempo de ejecución: el llamante ({@link NotificationService}) la registra y no la propaga.
     */
    void publish(NotificationResponse notification);
}
