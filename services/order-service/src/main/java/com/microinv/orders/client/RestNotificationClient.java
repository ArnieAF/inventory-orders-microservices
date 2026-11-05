package com.microinv.orders.client;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Cliente REST de {@code POST {NOTIFICATION_SERVICE_URL}/internal/notifications} con {@code X-Internal-Key}. */
public class RestNotificationClient implements NotificationClient {

    private final RestClient client;
    private final String internalKey;

    public RestNotificationClient(String baseUrl, String internalKey, int connectTimeoutMs, int readTimeoutMs) {
        this.client = InternalHttp.restClient(baseUrl, connectTimeoutMs, readTimeoutMs);
        this.internalKey = internalKey;
    }

    @Override
    public void send(NotificationRequest request) {
        client.post()
                .uri("/internal/notifications")
                .header(InternalHttp.INTERNAL_KEY_HEADER, internalKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange((req, response) -> {
                    InternalHttp.readLimited(response.getBody());
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        // Solo el codigo HTTP: nunca el cuerpo ni cabeceras.
                        throw new NotificationDeliveryException(response.getStatusCode().value());
                    }
                    return null;
                });
    }

    /** La entrega fallo con un estado HTTP inesperado. */
    public static class NotificationDeliveryException extends RuntimeException {
        public NotificationDeliveryException(int status) {
            super("Notification service answered HTTP " + status);
        }
    }
}
