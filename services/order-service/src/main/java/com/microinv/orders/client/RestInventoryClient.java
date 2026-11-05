package com.microinv.orders.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.orders.error.ApiException;
import com.microinv.orders.error.ErrorCode;
import com.microinv.orders.error.InventoryRejectedException;
import com.microinv.orders.error.InventoryUnavailableException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente REST de inventory-service ({@code /internal/stock/reserve} y {@code /release}, header
 * {@code X-Internal-Key}). Los 409/404 conocidos se propagan como {@link InventoryRejectedException} con el
 * mismo codigo y {@code details}; cualquier otro fallo es {@link InventoryUnavailableException} (503 al
 * cliente, sin detalles internos).
 */
public class RestInventoryClient implements InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(RestInventoryClient.class);

    private static final int MAX_DETAIL_ENTRIES = 50;
    private static final int MAX_DETAIL_FIELDS = 10;
    private static final int MAX_DETAIL_TEXT = 200;

    private final RestClient client;
    private final String internalKey;
    private final ObjectMapper objectMapper;

    public RestInventoryClient(String baseUrl, String internalKey, int connectTimeoutMs, int readTimeoutMs,
                               ObjectMapper objectMapper) {
        this.client = InternalHttp.restClient(baseUrl, connectTimeoutMs, readTimeoutMs);
        this.internalKey = internalKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReserveResult reserve(UUID orderRef, List<ReserveItem> items) {
        try {
            return client.post()
                    .uri("/internal/stock/reserve")
                    .header(InternalHttp.INTERNAL_KEY_HEADER, internalKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReserveRequest(orderRef, items))
                    .exchange((request, response) -> handleReserve(response));
        } catch (ApiException e) {
            throw e;
        } catch (ResourceAccessException e) {
            boolean neverReached = InternalHttp.isConnectFailure(e);
            log.warn("Inventory reserve failed ({}): {}", neverReached ? "connect" : "io",
                    e.getClass().getSimpleName());
            throw new InventoryUnavailableException(!neverReached, e);
        } catch (RestClientException e) {
            log.warn("Inventory reserve failed: {}", e.getClass().getSimpleName());
            throw new InventoryUnavailableException(true, e);
        }
    }

    @Override
    public void release(UUID orderRef) {
        try {
            client.post()
                    .uri("/internal/stock/release")
                    .header(InternalHttp.INTERNAL_KEY_HEADER, internalKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReleaseRequest(orderRef))
                    .exchange((request, response) -> {
                        InternalHttp.readLimited(response.getBody());
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            log.warn("Inventory release answered HTTP {}", response.getStatusCode().value());
                            throw new InventoryUnavailableException(true, null);
                        }
                        return null;
                    });
        } catch (ApiException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("Inventory release failed: {}", e.getClass().getSimpleName());
            throw new InventoryUnavailableException(true, e);
        }
    }

    private ReserveResult handleReserve(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        byte[] body = InternalHttp.readLimited(response.getBody());
        if (response.getStatusCode().is2xxSuccessful()) {
            try {
                ReserveResult result = objectMapper.readValue(body, ReserveResult.class);
                if (result == null || result.items() == null) {
                    log.warn("Inventory reserve answered HTTP {} without items", status);
                    throw new InventoryUnavailableException(true, null);
                }
                return result;
            } catch (IOException e) {
                log.warn("Inventory reserve answered HTTP {} with an unreadable body", status);
                throw new InventoryUnavailableException(true, e);
            }
        }
        throw classifyError(status, body);
    }

    /**
     * Solo se propagan 409 INSUFFICIENT_STOCK/PRODUCT_INACTIVE y 404 NOT_FOUND, y unicamente si el cuerpo trae el
     * formato de error comun (contrato, seccion 5) con ese {@code code}: un 404 sin ese cuerpo (URL base
     * equivocada, proxy) es un fallo de infraestructura y acaba en 503. Cualquier otro codigo de inventario
     * (incluidos 409 INVALID_STATUS_TRANSITION y 400 VALIDATION_ERROR al re-reservar un orderRef) tambien es 503:
     * nunca se reenvian tal cual, porque un 409 INVALID_STATUS_TRANSITION confundiria con una transicion de pedido.
     */
    private ApiException classifyError(int status, byte[] body) {
        String code = null;
        List<Map<String, Object>> details = null;
        try {
            // BigDecimal para los decimales: los details se reenvian sin pasar por double.
            JsonNode root = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(body);
            if (isCommonErrorFormat(root, status)) {
                code = root.get("code").asText();
                details = sanitizeDetails(root.get("details"));
            }
        } catch (IOException ignored) {
            // cuerpo ilegible: se trata como fallo generico
        }

        if (status == 409 && "INSUFFICIENT_STOCK".equals(code)) {
            return new InventoryRejectedException(HttpStatus.CONFLICT, ErrorCode.INSUFFICIENT_STOCK,
                    "Insufficient stock for one or more products", details);
        }
        if (status == 409 && "PRODUCT_INACTIVE".equals(code)) {
            return new InventoryRejectedException(HttpStatus.CONFLICT, ErrorCode.PRODUCT_INACTIVE,
                    "One or more products are inactive", details);
        }
        if (status == 404 && "NOT_FOUND".equals(code)) {
            return new InventoryRejectedException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                    "One or more products were not found", details);
        }
        // 401/403 apuntan a una INTERNAL_API_KEY mal configurada: se registra, pero al cliente solo llega un 503.
        log.warn("Inventory reserve answered HTTP {} with code {} (not propagated)", status, loggable(code));
        // 503 SERVICE_UNAVAILABLE en formato comun: inventario agoto su timeout de bloqueo/transaccion (4 s, antes
        // que nuestros 5 s de lectura) y revirtio la reserva: fallo claro, no hay nada que compensar a ciegas.
        if (status == 503 && "SERVICE_UNAVAILABLE".equals(code)) {
            return new InventoryUnavailableException(false, null);
        }
        // Otros 5xx (o sin cuerpo reconocible): la reserva pudo completarse. 4xx inesperado: no se acepto.
        return new InventoryUnavailableException(status >= 500, null);
    }

    /** Formato comun: objeto con {@code status} igual al HTTP, {@code code} y {@code message} de texto. */
    private static boolean isCommonErrorFormat(JsonNode root, int httpStatus) {
        return root != null && root.isObject()
                && root.path("status").isInt() && root.path("status").asInt() == httpStatus
                && root.path("code").isTextual() && !root.path("code").asText().isBlank()
                && root.path("message").isTextual();
    }

    /** Solo se registran codigos con forma de codigo (MAYUSCULAS_Y_GUIONES_BAJOS): nada de texto libre ajeno. */
    private static String loggable(String code) {
        return code != null && code.matches("[A-Z_]{1,40}") ? code : "n/a";
    }

    /** Copia acotada de {@code details}: solo objetos planos de escalares, con limites de tamano. */
    static List<Map<String, Object>> sanitizeDetails(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode element : node) {
            if (result.size() >= MAX_DETAIL_ENTRIES) {
                break;
            }
            if (!element.isObject()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = element.fields();
            while (fields.hasNext() && entry.size() < MAX_DETAIL_FIELDS) {
                Map.Entry<String, JsonNode> field = fields.next();
                Object value = scalar(field.getValue());
                if (value != null && field.getKey().length() <= MAX_DETAIL_TEXT) {
                    entry.put(field.getKey(), value);
                }
            }
            if (!entry.isEmpty()) {
                result.add(entry);
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static Object scalar(JsonNode value) {
        if (value.isTextual()) {
            String text = value.asText();
            return text.length() > MAX_DETAIL_TEXT ? text.substring(0, MAX_DETAIL_TEXT) : text;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return null;
    }

    private record ReserveRequest(UUID orderRef, List<ReserveItem> items) {
    }

    private record ReleaseRequest(UUID orderRef) {
    }
}
