package com.microinv.notifications.ws;

import com.microinv.notifications.api.dto.NotificationResponse;
import com.microinv.notifications.service.NotificationPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Emite por STOMP: difusión ({@code targetUserId == null}) a {@code /topic/notifications}; dirigida a
 * {@code /user/queue/notifications} del usuario destino (solo sus sesiones la reciben).
 */
@Component
public class StompNotificationPublisher implements NotificationPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public StompNotificationPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publish(NotificationResponse notification) {
        if (notification.targetUserId() == null) {
            messagingTemplate.convertAndSend(StompDestinations.BROADCAST_TOPIC, notification);
        } else {
            messagingTemplate.convertAndSendToUser(notification.targetUserId().toString(),
                    StompDestinations.USER_QUEUE, notification);
        }
    }
}
