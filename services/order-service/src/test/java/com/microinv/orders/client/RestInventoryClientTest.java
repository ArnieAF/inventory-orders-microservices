package com.microinv.orders.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.microinv.orders.error.ErrorCode;
import com.microinv.orders.error.InventoryRejectedException;
import com.microinv.orders.error.InventoryUnavailableException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Contra un servidor HTTP real: cabeceras, cuerpo, propagacion de 409/404, 503 generico y timeouts de verdad. */
class RestInventoryClientTest {

    private static final String KEY = "test-only-internal-key";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final List<ReserveItem> ITEMS = List.of(new ReserveItem(PRODUCT, 3));

    private static RestInventoryClient client(String baseUrl) {
        return new RestInventoryClient(baseUrl, KEY, 2000, 5000, MAPPER);
    }

    private static String error(int status, String code, String details) {
        return "{\"timestamp\":\"2026-11-05T12:00:00Z\",\"status\":" + status + ",\"code\":\"" + code
                + "\",\"message\":\"internal wording that must not be forwarded\",\"path\":\"/internal/stock/reserve\""
                + (details == null ? "" : ",\"details\":" + details) + "}";
    }

    // ---- reserva OK -------------------------------------------------------------------------------------------

    @Test
    void reserveSendsTheContractRequestAndParsesTheSnapshotExactly() throws Exception {
        String body = "{\"items\":[{\"productId\":\"" + PRODUCT + "\",\"sku\":\"WID-1\",\"name\":\"Widget\","
                + "\"unitPrice\":19.99,\"quantity\":3,\"extra\":\"ignored\"}]}";
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(200, body))) {
            ReserveResult result = client(server.baseUrl()).reserve(ORDER, ITEMS);

            StubServer.Request sent = server.last();
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.path()).isEqualTo("/internal/stock/reserve");
            assertThat(sent.header("X-Internal-Key")).isEqualTo(KEY);
            assertThat(sent.header("Content-Type")).startsWith("application/json");
            JsonNode json = MAPPER.readTree(sent.body());
            assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("orderRef", "items");
            assertThat(json.get("orderRef").asText()).isEqualTo(ORDER.toString());
            assertThat(json.get("items").get(0).fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("productId", "quantity");
            assertThat(json.get("items").get(0).get("productId").asText()).isEqualTo(PRODUCT.toString());
            assertThat(json.get("items").get(0).get("quantity").asInt()).isEqualTo(3);

            assertThat(result.items()).hasSize(1);
            ReservedItem item = result.items().get(0);
            assertThat(item.productId()).isEqualTo(PRODUCT);
            assertThat(item.sku()).isEqualTo("WID-1");
            assertThat(item.name()).isEqualTo("Widget");
            assertThat(item.unitPrice()).isEqualTo(new BigDecimal("19.99")); // exacto, sin pasar por double
            assertThat(item.quantity()).isEqualTo(3);
        }
    }

    // ---- rechazos que se propagan ------------------------------------------------------------------------------

    @Test
    void insufficientStockIsPropagatedWithTheSameCodeAndDetails() throws Exception {
        String details = "[{\"productId\":\"" + PRODUCT + "\",\"requested\":5,\"available\":2}]";
        try (StubServer server = StubServer.start(
                r -> StubServer.Response.of(409, error(409, "INSUFFICIENT_STOCK", details)))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryRejectedException.class, e -> {
                        assertThat(e.status().value()).isEqualTo(409);
                        assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
                        assertThat(e.details()).hasSize(1);
                        assertThat(e.details().get(0)).containsEntry("productId", PRODUCT.toString())
                                .containsEntry("requested", 5L).containsEntry("available", 2L);
                        assertThat(e.getMessage()).doesNotContain("internal wording");
                    });
        }
    }

    /** Contrato 6.2: NOT_FOUND y PRODUCT_INACTIVE llevan {@code details:[{productId, message}]}. */
    @Test
    void productInactiveAndNotFoundArePropagatedWithTheDetailsInventoryEmits() throws Exception {
        String details = "[{\"productId\":\"" + PRODUCT + "\",\"message\":\"Product is inactive\"}]";
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(409,
                error(409, "PRODUCT_INACTIVE", details)))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryRejectedException.class, e -> {
                        assertThat(e.status().value()).isEqualTo(409);
                        assertThat(e.code()).isEqualTo(ErrorCode.PRODUCT_INACTIVE);
                        assertThat(e.details()).hasSize(1);
                        assertThat(e.details().get(0)).containsOnly(
                                java.util.Map.entry("productId", PRODUCT.toString()),
                                java.util.Map.entry("message", "Product is inactive"));
                    });
        }
        String notFoundDetails = "[{\"productId\":\"" + PRODUCT + "\",\"message\":\"Product not found\"}]";
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(404,
                error(404, "NOT_FOUND", notFoundDetails)))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryRejectedException.class, e -> {
                        assertThat(e.status().value()).isEqualTo(404);
                        assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
                        assertThat(e.details().get(0)).containsOnly(
                                java.util.Map.entry("productId", PRODUCT.toString()),
                                java.util.Map.entry("message", "Product not found"));
                    });
        }
    }

    @Test
    void notFoundWithoutDetailsIsStillPropagated() throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(404, error(404, "NOT_FOUND", null)))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryRejectedException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
                        assertThat(e.details()).isNull();
                    });
        }
    }

    /** Un 404 que no es del servicio de inventario (URL base equivocada, proxy) no puede pasar por NOT_FOUND. */
    @ParameterizedTest(name = "404 body={0} -> 503")
    @ValueSource(strings = {"", "<html><body>404 Not Found</body></html>", "{}", "not json",
            "{\"status\":404,\"error\":\"Not Found\",\"path\":\"/internal/stock/reserve\"}",
            "{\"code\":\"NOT_FOUND\"}",
            "{\"status\":404,\"code\":\"NOT_FOUND\"}",
            "{\"status\":200,\"code\":\"NOT_FOUND\",\"message\":\"x\"}",
            "{\"status\":\"404\",\"code\":\"NOT_FOUND\",\"message\":\"x\"}",
            "{\"status\":404,\"code\":404,\"message\":\"x\"}"})
    void a404WithoutTheCommonErrorBodyIsUnavailableNotNotFound(String body) throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(404, body))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryUnavailableException.class, e -> {
                        assertThat(e.status().value()).isEqualTo(503);
                        assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(e.isOutcomeUnknown()).isFalse();
                    });
        }
    }

    /**
     * Respuestas REALES de inventory al re-reservar un orderRef (contrato 6.2): no se propagan, ni siquiera como
     * 409: un INVALID_STATUS_TRANSITION de inventario se confundiria con una transicion de pedido.
     */
    @Test
    void inventoryReReserveErrorsAreNeverForwardedAsTheirOwnCode() throws Exception {
        String released = "{\"timestamp\":\"2026-11-05T12:00:00Z\",\"status\":409,\"code\":\"INVALID_STATUS_TRANSITION\","
                + "\"message\":\"Order reference was already released\",\"path\":\"/internal/stock/reserve\"}";
        String differentItems = "{\"timestamp\":\"2026-11-05T12:00:00Z\",\"status\":400,\"code\":\"VALIDATION_ERROR\","
                + "\"message\":\"Validation failed\",\"path\":\"/internal/stock/reserve\","
                + "\"details\":[{\"field\":\"items\",\"message\":\"orderRef was already reserved with different items\"}]}";
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(409, released))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryUnavailableException.class, e -> {
                        assertThat(e.status().value()).isEqualTo(503);
                        assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(e.getMessage()).doesNotContain("released").doesNotContain("INVALID");
                        assertThat(e.details()).isNull();
                    });
        }
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(400, differentItems))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryUnavailableException.class, e -> {
                        assertThat(e.status().value()).isEqualTo(503);
                        assertThat(e.details()).isNull();
                    });
        }
    }

    /**
     * 503 SERVICE_UNAVAILABLE de inventory (timeout de bloqueo de 4 s, con Retry-After) en formato comun: la reserva
     * se revirtio, resultado CLARO, no incierto: no se compensa a ciegas. Un 503 sin ese cuerpo (proxy, caida)
     * si es incierto.
     */
    @Test
    void inventoryLockTimeout503IsAClearFailureButAnOpaque503IsUncertain() throws Exception {
        String clear = error(503, "SERVICE_UNAVAILABLE", null);
        try (StubServer server = StubServer.start(r -> StubServer.Response.withRetryAfter(503, clear, "1"))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryUnavailableException.class, e -> {
                        assertThat(e.isOutcomeUnknown()).isFalse();
                        assertThat(e.status().value()).isEqualTo(503);
                    });
        }
        for (String opaque : new String[] {"", "<html>Service Unavailable</html>", "{\"status\":503}"}) {
            try (StubServer server = StubServer.start(r -> StubServer.Response.of(503, opaque))) {
                assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                        .as("body: %s", opaque)
                        .isInstanceOfSatisfying(InventoryUnavailableException.class,
                                e -> assertThat(e.isOutcomeUnknown()).isTrue());
            }
        }
    }

    @Test
    void rejectionWithoutDetailsIsStillPropagated() throws Exception {
        try (StubServer server = StubServer.start(
                r -> StubServer.Response.of(409, error(409, "INSUFFICIENT_STOCK", null)))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryRejectedException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
                        assertThat(e.details()).isNull();
                    });
        }
    }

    // ---- todo lo demas es 503 ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "HTTP {0} code={1} -> unavailable, outcomeUnknown={2}")
    @CsvSource({
            "400, VALIDATION_ERROR, false",
            "401, UNAUTHORIZED, false",
            "403, FORBIDDEN, false",
            "404, SOMETHING_ELSE, false",
            "409, SKU_ALREADY_EXISTS, false",
            "409, INVALID_STATUS_TRANSITION, false",
            "409, NOT_FOUND, false",
            "404, INSUFFICIENT_STOCK, false",
            "500, INTERNAL_ERROR, true",
            "502, BAD_GATEWAY, true",
            "503, INTERNAL_ERROR, true"})
    void anyOtherErrorStatusIsUnavailable(int status, String code, boolean outcomeUnknown) throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(status, error(status, code, null)))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryUnavailableException.class, e -> {
                        assertThat(e.isOutcomeUnknown()).isEqualTo(outcomeUnknown);
                        assertThat(e.status().value()).isEqualTo(503);
                        assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(e.getMessage()).doesNotContain("internal wording");
                    });
        }
    }

    @Test
    void emptyAndNonJsonErrorBodiesAreUnavailable() throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(500, "<html>boom</html>"))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOf(InventoryUnavailableException.class);
        }
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(409, ""))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOf(InventoryUnavailableException.class);
        }
    }

    @Test
    void unusable200BodiesAreUnavailableWithUnknownOutcome() throws Exception {
        for (String body : new String[] {"", "not json", "{}", "{\"items\":null}", "[]", "{\"items\":\"x\"}"}) {
            try (StubServer server = StubServer.start(r -> StubServer.Response.of(200, body))) {
                assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                        .as("body: %s", body)
                        .isInstanceOfSatisfying(InventoryUnavailableException.class,
                                e -> assertThat(e.isOutcomeUnknown()).isTrue());
            }
        }
    }

    /** X-Internal-Key jamas viaja a otra URL: un 3xx no se sigue (ni siquiera con Location valido) y es un fallo. */
    @ParameterizedTest(name = "HTTP {0} with Location is not followed")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void redirectsAreNeverFollowedAndTheInternalKeyNeverReachesAnotherUrl(int status) throws Exception {
        try (StubServer elsewhere = StubServer.start(r -> StubServer.Response.of(200, "{\"items\":[]}"));
                StubServer server = StubServer.start(
                        r -> StubServer.Response.redirect(status, elsewhere.baseUrl() + "/internal/stock/reserve"))) {
            RestInventoryClient client = client(server.baseUrl());

            assertThatThrownBy(() -> client.reserve(ORDER, ITEMS)).isInstanceOf(InventoryUnavailableException.class);
            assertThatThrownBy(() -> client.release(ORDER)).isInstanceOf(InventoryUnavailableException.class);

            assertThat(server.requests).hasSize(2); // reserve y release: una peticion cada una, sin reintentos
            assertThat(elsewhere.requests).isEmpty(); // la otra URL no recibio nada (ni la clave)
        }
    }

    @Test
    void anUnfollowedRedirectOnReserveIsAClearFailure() throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.redirect(307, "http://127.0.0.1:1/x"))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryUnavailableException.class,
                            e -> assertThat(e.isOutcomeUnknown()).isFalse());
        }
    }

    // ---- red: conexion rechazada y timeouts -------------------------------------------------------------------

    @Test
    void connectionRefusedIsUnavailableAndTheRequestNeverArrived() throws Exception {
        RestInventoryClient client = client(StubServer.closedPortUrl());

        assertThatThrownBy(() -> client.reserve(ORDER, ITEMS))
                .isInstanceOfSatisfying(InventoryUnavailableException.class,
                        e -> assertThat(e.isOutcomeUnknown()).isFalse());
        assertThatThrownBy(() -> client.release(ORDER)).isInstanceOf(InventoryUnavailableException.class);
    }

    @Test
    void readTimeoutIsUnavailableWithUnknownOutcomeAndReturnsPromptly() throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.slow(3000))) {
            RestInventoryClient client = new RestInventoryClient(server.baseUrl(), KEY, 2000, 300, MAPPER);
            long start = System.nanoTime();

            assertThatThrownBy(() -> client.reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryUnavailableException.class,
                            e -> assertThat(e.isOutcomeUnknown()).isTrue());

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isLessThan(2500); // no espero los 3 s del servidor
        }
    }

    // ---- detalles: se copian con limites ----------------------------------------------------------------------

    @Test
    void detailsAreSanitizedAndBounded() throws Exception {
        String many = IntStream.range(0, 80)
                .mapToObj(i -> "{\"productId\":\"p" + i + "\"}").collect(Collectors.joining(",", "[", "]"));
        try (StubServer server = StubServer.start(
                r -> StubServer.Response.of(409, error(409, "INSUFFICIENT_STOCK", many)))) {
            assertThatThrownBy(() -> client(server.baseUrl()).reserve(ORDER, ITEMS))
                    .isInstanceOfSatisfying(InventoryRejectedException.class,
                            e -> assertThat(e.details()).hasSize(50));
        }

        String weird = "[{\"a\":\"" + "x".repeat(500) + "\",\"nested\":{\"b\":1},\"list\":[1,2],\"n\":null,"
                + "\"price\":1.50,\"ok\":true},\"scalar\",[1],{}]";
        JsonNode node = MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature
                .USE_BIG_DECIMAL_FOR_FLOATS).readTree(weird);
        List<java.util.Map<String, Object>> sanitized = RestInventoryClient.sanitizeDetails(node);
        assertThat(sanitized).hasSize(1);
        assertThat(sanitized.get(0)).containsOnlyKeys("a", "price", "ok");
        assertThat((String) sanitized.get(0).get("a")).hasSize(200);
        assertThat((BigDecimal) sanitized.get(0).get("price")).isEqualByComparingTo("1.50");
        assertThat(RestInventoryClient.sanitizeDetails(MAPPER.readTree("{\"x\":1}"))).isNull();
        assertThat(RestInventoryClient.sanitizeDetails(null)).isNull();
    }

    // ---- release ----------------------------------------------------------------------------------------------

    @Test
    void releaseSendsOnlyTheOrderRefWithTheInternalKeyAndAccepts204() throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(204, null))) {
            assertThatCode(() -> client(server.baseUrl()).release(ORDER)).doesNotThrowAnyException();

            StubServer.Request sent = server.last();
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.path()).isEqualTo("/internal/stock/release");
            assertThat(sent.header("X-Internal-Key")).isEqualTo(KEY);
            JsonNode json = MAPPER.readTree(sent.body());
            assertThat(json.fieldNames()).toIterable().containsExactly("orderRef");
            assertThat(json.get("orderRef").asText()).isEqualTo(ORDER.toString());
        }
    }

    @ParameterizedTest(name = "release answered HTTP {0} -> unavailable")
    @CsvSource({"400", "401", "404", "409", "500", "503", "302"})
    void releaseWithAnyNon2xxIsUnavailable(int status) throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(status, error(status, "X", null)))) {
            assertThatThrownBy(() -> client(server.baseUrl()).release(ORDER))
                    .isInstanceOf(InventoryUnavailableException.class);
        }
    }

    @Test
    void releaseTimesOutAsUnavailable() throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.slow(3000))) {
            RestInventoryClient client = new RestInventoryClient(server.baseUrl(), KEY, 2000, 300, MAPPER);
            assertThatThrownBy(() -> client.release(ORDER)).isInstanceOf(InventoryUnavailableException.class);
        }
    }
}
