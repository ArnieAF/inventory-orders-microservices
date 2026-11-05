package com.microinv.orders.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RestNotificationClientTest {

    private static final String KEY = "test-only-internal-key";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private static NotificationRequest broadcast() {
        return new NotificationRequest(NotificationType.NEW_ORDER, "New order", "Order #ABCD1234 was created", null,
                USER, "ORDER", ORDER);
    }

    @Test
    void postsTheContractBodyWithTheInternalKey() throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(201, "{}"))) {
            new RestNotificationClient(server.baseUrl(), KEY, 2000, 5000).send(broadcast());

            StubServer.Request sent = server.last();
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.path()).isEqualTo("/internal/notifications");
            assertThat(sent.header("X-Internal-Key")).isEqualTo(KEY);
            assertThat(sent.header("Content-Type")).startsWith("application/json");
            JsonNode json = MAPPER.readTree(sent.body());
            assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("type", "title", "message",
                    "targetUserId", "actorUserId", "entityType", "entityId");
            assertThat(json.get("type").asText()).isEqualTo("NEW_ORDER");
            assertThat(json.get("targetUserId").isNull()).isTrue(); // broadcast
            assertThat(json.get("actorUserId").asText()).isEqualTo(USER.toString());
            assertThat(json.get("entityType").asText()).isEqualTo("ORDER");
            assertThat(json.get("entityId").asText()).isEqualTo(ORDER.toString());
        }
    }

    @Test
    void directedNotificationCarriesTheTargetUser() throws Exception {
        UUID actor = UUID.randomUUID();
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(201, "{}"))) {
            new RestNotificationClient(server.baseUrl(), KEY, 2000, 5000).send(new NotificationRequest(
                    NotificationType.ORDER_STATUS_CHANGED, "Order status changed", "m", USER, actor, "ORDER", ORDER));

            JsonNode json = MAPPER.readTree(server.last().body());
            assertThat(json.get("type").asText()).isEqualTo("ORDER_STATUS_CHANGED");
            assertThat(json.get("targetUserId").asText()).isEqualTo(USER.toString());
            assertThat(json.get("actorUserId").asText()).isEqualTo(actor.toString());
        }
    }

    @ParameterizedTest
    @CsvSource({"200", "201", "204"})
    void anySuccessStatusIsFine(int status) throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(status, status == 204 ? null : "{}"))) {
            assertThatCode(() -> new RestNotificationClient(server.baseUrl(), KEY, 2000, 5000).send(broadcast()))
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @CsvSource({"400", "401", "404", "500", "503"})
    void errorStatusesRaiseAnExceptionThatOnlyMentionsTheStatus(int status) throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(status, "{\"secret\":\"body\"}"))) {
            assertThatThrownBy(() -> new RestNotificationClient(server.baseUrl(), KEY, 2000, 5000).send(broadcast()))
                    .isInstanceOf(RestNotificationClient.NotificationDeliveryException.class)
                    .hasMessageContaining(String.valueOf(status))
                    .hasMessageNotContaining("secret");
        }
    }

    @ParameterizedTest
    @CsvSource({"301", "302", "303", "307", "308"})
    void redirectsAreNeverFollowedSoTheInternalKeyStaysOnTheConfiguredUrl(int status) throws Exception {
        try (StubServer elsewhere = StubServer.start(r -> StubServer.Response.of(201, "{}"));
                StubServer server = StubServer.start(
                        r -> StubServer.Response.redirect(status, elsewhere.baseUrl() + "/internal/notifications"))) {
            assertThatThrownBy(() -> new RestNotificationClient(server.baseUrl(), KEY, 2000, 5000).send(broadcast()))
                    .isInstanceOf(RestNotificationClient.NotificationDeliveryException.class)
                    .hasMessageContaining(String.valueOf(status));

            assertThat(server.requests).hasSize(1);
            assertThat(elsewhere.requests).isEmpty();
        }
    }

    @Test
    void spanishTextsTravelAsUtf8() throws Exception {
        try (StubServer server = StubServer.start(r -> StubServer.Response.of(201, "{}"))) {
            new RestNotificationClient(server.baseUrl(), KEY, 2000, 5000).send(new NotificationRequest(
                    NotificationType.NEW_ORDER, "Nuevo pedido", "Se creó el pedido #ABCD1234", null, USER,
                    "ORDER", ORDER));

            JsonNode json = MAPPER.readTree(server.last().body());
            assertThat(json.get("message").asText()).isEqualTo("Se creó el pedido #ABCD1234");
        }
    }

    @Test
    void connectionRefusedAndReadTimeoutRaiseExceptions() throws Exception {
        assertThatThrownBy(() -> new RestNotificationClient(StubServer.closedPortUrl(), KEY, 2000, 5000)
                .send(broadcast())).isInstanceOf(RuntimeException.class);

        try (StubServer server = StubServer.start(r -> StubServer.Response.slow(3000))) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> new RestNotificationClient(server.baseUrl(), KEY, 2000, 300).send(broadcast()))
                    .isInstanceOf(RuntimeException.class);
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2500);
        }
    }
}
