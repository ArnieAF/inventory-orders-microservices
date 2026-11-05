package com.microinv.inventory.notification;

import com.microinv.inventory.config.NotificationProperties;
import com.microinv.inventory.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Implementacion HTTP (contrato 3 y 6.4): {@code POST {NOTIFICATION_SERVICE_URL}/internal/notifications}
 * con {@code X-Internal-Key}, timeouts de conexion 2 s y lectura 5 s. Best-effort: cualquier fallo se
 * registra (solo tipo de error y codigo HTTP, nunca el cuerpo, la clave ni datos del producto) y se ignora.
 */
@Component
public class HttpNotificationClient implements NotificationClient {

    static final String PATH = "/internal/notifications";

    private static final Logger log = LoggerFactory.getLogger(HttpNotificationClient.class);

    private final RestClient restClient;
    private final String internalKey;

    public HttpNotificationClient(RestClient.Builder builder, NotificationProperties properties,
                                  SecurityProperties security) {
        // HttpURLConnection no sigue redirecciones en POST: la clave interna nunca viaja a otro destino.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        this.internalKey = security.internalApiKey();
    }

    @Override
    public void send(NotificationRequest request) {
        try {
            restClient.post()
                    .uri(PATH)
                    .header("X-Internal-Key", internalKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.warn("notification-service rejected the notification (type={}, status={})",
                    request.type(), e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Could not deliver notification (type={}, cause={})",
                    request.type(), e.getClass().getSimpleName());
        }
    }
}
