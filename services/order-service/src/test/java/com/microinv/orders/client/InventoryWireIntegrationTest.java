package com.microinv.orders.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.orders.TestTokens;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * order-service completo (seguridad, BD, cliente REST REAL de inventario) contra un inventario simulado por HTTP
 * que responde con los cuerpos reales del contrato 6.2: comprueba de punta a punta lo que llega al cliente de la
 * API y las llamadas que inventario recibe (incluida la compensacion con release).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryWireIntegrationTest {

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String INTERNAL_KEY = "test-only-internal-key"; // application-test.yml
    private static final UUID PRODUCT = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final AtomicReference<Function<StubServer.Request, StubServer.Response>> BEHAVIOR =
            new AtomicReference<>();
    private static final StubServer INVENTORY;
    /** Segunda URL: no debe recibir NUNCA nada (adonde apuntarian las redirecciones). */
    private static final StubServer ELSEWHERE;

    static {
        try {
            INVENTORY = StubServer.start(r -> BEHAVIOR.get().apply(r));
            ELSEWHERE = StubServer.start(r -> StubServer.Response.of(200, "{\"items\":[]}"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("app.inventory.base-url", INVENTORY::baseUrl);
    }

    @MockitoBean
    NotificationClient notifications;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void reset() {
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
        INVENTORY.requests.clear();
        ELSEWHERE.requests.clear();
    }

    private static String error(int status, String code, String message, String details) {
        return "{\"timestamp\":\"2026-11-05T12:00:00.000Z\",\"status\":" + status + ",\"code\":\"" + code
                + "\",\"message\":\"" + message + "\",\"path\":\"/internal/stock/reserve\""
                + (details == null ? "" : ",\"details\":" + details) + "}";
    }

    private void inventoryAnswersReserveWith(StubServer.Response response) {
        BEHAVIOR.set(request -> request.path().endsWith("/reserve") ? response : StubServer.Response.of(204, null));
    }

    private org.springframework.test.web.servlet.ResultActions createOrder() throws Exception {
        String body = "{\"customerName\":\"ACME\",\"items\":[{\"productId\":\"" + PRODUCT + "\",\"quantity\":2}]}";
        return mvc.perform(post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid(USER, "alice"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long orders() {
        Long count = jdbc.queryForObject("select count(*) from orders", Long.class);
        return count == null ? 0 : count;
    }

    private List<String> inventoryPaths() {
        return INVENTORY.requests.stream().map(StubServer.Request::path).toList();
    }

    // ---- caso feliz --------------------------------------------------------------------------------------------

    @Test
    void createsTheOrderWithThePriceAndSnapshotInventoryReturnsAndCallsItAsTheContractSays() throws Exception {
        inventoryAnswersReserveWith(StubServer.Response.of(200, "{\"items\":[{\"productId\":\"" + PRODUCT
                + "\",\"sku\":\"WID-1\",\"name\":\"Widget\",\"unitPrice\":12.50,\"quantity\":2}]}"));

        String responseBody = createOrder()
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"total\":25.00")))
                .andExpect(jsonPath("$.items[0].sku").value("WID-1"))
                .andReturn().getResponse().getContentAsString();

        UUID orderId = UUID.fromString(json.readTree(responseBody).get("id").asText());
        assertThat(inventoryPaths()).containsExactly("/internal/stock/reserve"); // sin release
        StubServer.Request reserve = INVENTORY.last();
        assertThat(reserve.header("X-Internal-Key")).isEqualTo(INTERNAL_KEY);
        JsonNode sent = json.readTree(reserve.body());
        assertThat(sent.get("orderRef").asText()).isEqualTo(orderId.toString());
        assertThat(sent.get("items").get(0).get("productId").asText()).isEqualTo(PRODUCT.toString());
        assertThat(sent.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(orders()).isEqualTo(1);
    }

    // ---- rechazos documentados: se propagan con el mismo codigo y details -----------------------------------

    @Test
    void insufficientStockIsPropagatedWithInventorysDetails() throws Exception {
        String details = "[{\"productId\":\"" + PRODUCT + "\",\"requested\":5,\"available\":2}]";
        inventoryAnswersReserveWith(StubServer.Response.of(409,
                error(409, "INSUFFICIENT_STOCK", "Insufficient stock", details)));

        createOrder()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details[0].productId").value(PRODUCT.toString()))
                .andExpect(jsonPath("$.details[0].requested").value(5))
                .andExpect(jsonPath("$.details[0].available").value(2));

        assertThat(orders()).isZero();
        assertThat(inventoryPaths()).containsExactly("/internal/stock/reserve");
    }

    @Test
    void productInactiveAndNotFoundArePropagatedWithTheirProductIdAndMessageDetails() throws Exception {
        String details = "[{\"productId\":\"" + PRODUCT + "\",\"message\":\"Product is inactive\"}]";
        inventoryAnswersReserveWith(StubServer.Response.of(409,
                error(409, "PRODUCT_INACTIVE", "Product is inactive", details)));
        createOrder()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"))
                .andExpect(jsonPath("$.details[0].productId").value(PRODUCT.toString()))
                .andExpect(jsonPath("$.details[0].message").value("Product is inactive"));

        inventoryAnswersReserveWith(StubServer.Response.of(404, error(404, "NOT_FOUND", "Product not found",
                "[{\"productId\":\"" + PRODUCT + "\",\"message\":\"Product not found\"}]")));
        createOrder()
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.details[0].productId").value(PRODUCT.toString()))
                .andExpect(jsonPath("$.details[0].message").value("Product not found"));

        assertThat(orders()).isZero();
    }

    // ---- lo que NO se propaga ---------------------------------------------------------------------------------

    @Test
    void inventoryReReserveErrorsBecomeAGeneric503NeverAnInvalidStatusTransition() throws Exception {
        inventoryAnswersReserveWith(StubServer.Response.of(409, error(409, "INVALID_STATUS_TRANSITION",
                "Order reference was already released", null)));
        createOrder()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("INVALID_STATUS_TRANSITION"))))
                .andExpect(content().string(not(containsString("released"))));

        inventoryAnswersReserveWith(StubServer.Response.of(400, error(400, "VALIDATION_ERROR", "Validation failed",
                "[{\"field\":\"items\",\"message\":\"orderRef was already reserved with different items\"}]")));
        createOrder()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(content().string(not(containsString("different items"))));

        assertThat(orders()).isZero();
        assertThat(inventoryPaths()).containsOnly("/internal/stock/reserve"); // rechazo claro: nada que compensar
    }

    @Test
    void aNotFoundThatIsNotInventorysCommonErrorBodyIsA503() throws Exception {
        // URL base equivocada: el servidor contesta 404 con una pagina cualquiera
        inventoryAnswersReserveWith(StubServer.Response.of(404, "<html>Not Found</html>"));
        createOrder()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        assertThat(orders()).isZero();
    }

    // ---- 503 de inventario: claro frente a incierto ----------------------------------------------------------

    @Test
    void inventoryLockTimeout503IsAClearFailureSoNothingIsCompensatedBlindly() throws Exception {
        inventoryAnswersReserveWith(StubServer.Response.withRetryAfter(503,
                error(503, "SERVICE_UNAVAILABLE", "Temporarily unavailable, please retry", null), "1"));

        createOrder()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        assertThat(orders()).isZero();
        assertThat(inventoryPaths()).containsExactly("/internal/stock/reserve"); // la reserva se revirtio: sin release
    }

    @Test
    void anOpaque503FromInventoryIsUncertainSoTheReservationIsCompensatedWithTheSameOrderRef() throws Exception {
        inventoryAnswersReserveWith(StubServer.Response.of(503, ""));

        createOrder().andExpect(status().isServiceUnavailable());

        assertThat(orders()).isZero();
        assertThat(inventoryPaths()).containsExactly("/internal/stock/reserve", "/internal/stock/release");
        UUID reserved = UUID.fromString(json.readTree(INVENTORY.requests.get(0).body()).get("orderRef").asText());
        JsonNode release = json.readTree(INVENTORY.requests.get(1).body());
        assertThat(release.get("orderRef").asText()).isEqualTo(reserved.toString());
        assertThat(INVENTORY.requests.get(1).header("X-Internal-Key")).isEqualTo(INTERNAL_KEY);
    }

    // ---- redirecciones ----------------------------------------------------------------------------------------

    @Test
    void aRedirectFromInventoryIsNeverFollowedAndTheKeyNeverReachesAnotherUrl() throws Exception {
        BEHAVIOR.set(request -> StubServer.Response.redirect(307, ELSEWHERE.baseUrl() + request.path()));

        createOrder().andExpect(status().isServiceUnavailable());

        assertThat(ELSEWHERE.requests).isEmpty();
        assertThat(orders()).isZero();
    }

    // ---- cancelacion contra el cliente real -------------------------------------------------------------------

    private UUID seedPending() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("insert into orders (id, customer_name, notes, status, total, created_by, created_by_username,"
                + " created_at, updated_at, version) values (?, 'ACME', null, 'PENDING', 1.00, ?, 'bob', ?, ?, 0)",
                id, USER, now, now);
        return id;
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, id);
    }

    @Test
    void cancellationCallsReleaseWithTheOrderIdAndKeyAndConfirmsOn204() throws Exception {
        UUID id = seedPending();
        BEHAVIOR.set(request -> StubServer.Response.of(204, null));

        mvc.perform(patch("/api/orders/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid(USER, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(inventoryPaths()).containsExactly("/internal/stock/release");
        assertThat(json.readTree(INVENTORY.last().body()).get("orderRef").asText()).isEqualTo(id.toString());
        assertThat(INVENTORY.last().header("X-Internal-Key")).isEqualTo(INTERNAL_KEY);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
    }

    @Test
    void ifInventoryFailsToReleaseTheCancellationIs503AndTheOrderDoesNotChange() throws Exception {
        UUID id = seedPending();
        for (StubServer.Response failure : List.of(
                StubServer.Response.of(500, error(500, "INTERNAL_ERROR", "Unexpected error", null)),
                StubServer.Response.withRetryAfter(503, error(503, "SERVICE_UNAVAILABLE", "Temporarily unavailable",
                        null), "1"),
                StubServer.Response.of(404, "<html>Not Found</html>"),
                StubServer.Response.redirect(307, ELSEWHERE.baseUrl() + "/internal/stock/release"))) {
            BEHAVIOR.set(request -> failure);

            mvc.perform(patch("/api/orders/" + id + "/status")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid(USER, "alice"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

            assertThat(statusOf(id)).isEqualTo("PENDING");
        }
        assertThat(ELSEWHERE.requests).isEmpty();
    }
}
