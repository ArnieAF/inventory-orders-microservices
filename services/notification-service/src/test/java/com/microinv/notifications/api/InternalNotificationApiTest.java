package com.microinv.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microinv.notifications.support.IntegrationTestBase;
import com.microinv.notifications.support.TestTokens;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** {@code POST /internal/notifications}: autenticación por clave interna, validación del cuerpo y persistencia. */
class InternalNotificationApiTest extends IntegrationTestBase {

    private static final String KEY_HEADER = "X-Internal-Key";

    @Autowired
    private ObjectMapper objectMapper;

    private final UUID userA = UUID.randomUUID();

    private ObjectNode validBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "NEW_ORDER");
        body.put("title", "New order");
        body.put("message", "Order #1 was created");
        body.putNull("targetUserId");
        body.put("actorUserId", UUID.randomUUID().toString());
        body.put("entityType", "ORDER");
        body.put("entityId", UUID.randomUUID().toString());
        return body;
    }

    private MockHttpServletRequestBuilder request(String key, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = post("/internal/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : objectMapper.writeValueAsString(body));
        if (key != null) {
            builder.header(KEY_HEADER, key);
        }
        return builder;
    }

    private ResultActions send(Object body) throws Exception {
        return mvc.perform(request(TestTokens.INTERNAL_KEY, body));
    }

    private long countNotifications() {
        Long count = jdbc.queryForObject("select count(*) from notifications", Long.class);
        return count == null ? 0 : count;
    }

    // ---- Autenticación

    @Test
    void withoutTheKeyIsUnauthorizedAndPersistsNothing() throws Exception {
        mvc.perform(request(null, validBody())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/internal/notifications"));
        assertThat(countNotifications()).isZero();
    }

    @Test
    void withAWrongKeyIsUnauthorizedAndPersistsNothing() throws Exception {
        String[] wrongKeys = {"wrong", "", " ", TestTokens.INTERNAL_KEY + "x", TestTokens.INTERNAL_KEY.substring(1),
                TestTokens.INTERNAL_KEY.toUpperCase(), TestTokens.INTERNAL_KEY + " "};
        for (String key : wrongKeys) {
            mvc.perform(request(key, validBody())).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        assertThat(countNotifications()).isZero();
    }

    @Test
    void aValidJwtWithoutTheKeyIsRejected() throws Exception {
        mvc.perform(request(null, validBody()).header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(countNotifications()).isZero();
    }

    @Test
    void aValidJwtDoesNotReplaceAWrongKey() throws Exception {
        mvc.perform(request("wrong", validBody()).header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isUnauthorized());
        assertThat(countNotifications()).isZero();
    }

    @Test
    void theKeyIsNotAcceptedOnTheJwtProtectedApi() throws Exception {
        mvc.perform(get("/api/notifications").header(KEY_HEADER, TestTokens.INTERNAL_KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyInternalPathAndMethodRequiresTheKey() throws Exception {
        mvc.perform(get("/internal/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/anything")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/anything")).andExpect(status().isUnauthorized());
    }

    // ---- Éxito

    @Test
    void createsABroadcastAndReturnsTheNotification() throws Exception {
        ObjectNode body = validBody();
        long before = countNotifications();
        ResultActions result = send(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("NEW_ORDER"))
                .andExpect(jsonPath("$.title").value("New order"))
                .andExpect(jsonPath("$.message").value("Order #1 was created"))
                .andExpect(jsonPath("$.targetUserId").doesNotExist())
                .andExpect(jsonPath("$.actorUserId").value(body.get("actorUserId").asText()))
                .andExpect(jsonPath("$.entityType").value("ORDER"))
                .andExpect(jsonPath("$.entityId").value(body.get("entityId").asText()))
                .andExpect(jsonPath("$.read").value(false));
        JsonNode created = objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        assertThat(Instant.parse(created.get("createdAt").asText())).isBefore(Instant.now().plusSeconds(5));
        assertThat(created.get("targetUserId").isNull()).isTrue();

        assertThat(countNotifications()).isEqualTo(before + 1);
        Integer stored = jdbc.queryForObject(
                "select count(*) from notifications where id = ? and target_user_id is null and type = 'NEW_ORDER'",
                Integer.class, UUID.fromString(created.get("id").asText()));
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void createsATargetedNotificationVisibleOnlyToItsRecipient() throws Exception {
        ObjectNode body = validBody();
        body.put("type", "ORDER_STATUS_CHANGED");
        body.put("targetUserId", userA.toString());
        send(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetUserId").value(userA.toString()));

        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void actorIsOptional() throws Exception {
        ObjectNode body = validBody();
        body.remove("actorUserId");
        send(body).andExpect(status().isCreated()).andExpect(jsonPath("$.actorUserId").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({"NEW_ORDER,ORDER", "LOW_STOCK,PRODUCT", "ORDER_STATUS_CHANGED,ORDER", "LOW_STOCK,ORDER"})
    void acceptsEveryContractTypeAndEntityType(String type, String entityType) throws Exception {
        ObjectNode body = validBody();
        body.put("type", type);
        body.put("entityType", entityType);
        send(body).andExpect(status().isCreated()).andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.entityType").value(entityType));
    }

    @Test
    void lengthLimitsAreInclusive() throws Exception {
        ObjectNode body = validBody();
        body.put("title", "t".repeat(120));
        body.put("message", "m".repeat(500));
        send(body).andExpect(status().isCreated());
    }

    @Test
    void acceptsUnicodeText() throws Exception {
        ObjectNode body = validBody();
        body.put("title", "Pedido nuevo ñá ☃");
        body.put("message", "Cliente: José 中文 😀");
        send(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Pedido nuevo ñá ☃"))
                .andExpect(jsonPath("$.message").value("Cliente: José 中文 😀"));
    }

    // ---- Validación del cuerpo

    private void assertInvalid(ObjectNode body, String field) throws Exception {
        long before = countNotifications();
        send(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value(field))
                .andExpect(jsonPath("$.path").value("/internal/notifications"));
        assertThat(countNotifications()).isEqualTo(before);
    }

    @Test
    void rejectsAnUnknownOrMissingType() throws Exception {
        ObjectNode unknown = validBody();
        unknown.put("type", "SOMETHING_ELSE");
        assertInvalid(unknown, "type");

        ObjectNode lowercase = validBody();
        lowercase.put("type", "new_order");
        assertInvalid(lowercase, "type");

        ObjectNode missing = validBody();
        missing.remove("type");
        assertInvalid(missing, "type");

        ObjectNode nullType = validBody();
        nullType.putNull("type");
        assertInvalid(nullType, "type");
    }

    @Test
    void rejectsAnUnknownOrMissingEntityType() throws Exception {
        ObjectNode unknown = validBody();
        unknown.put("entityType", "USER");
        assertInvalid(unknown, "entityType");

        ObjectNode missing = validBody();
        missing.remove("entityType");
        assertInvalid(missing, "entityType");
    }

    @Test
    void rejectsTitlesThatAreTooLongOrBlank() throws Exception {
        ObjectNode longTitle = validBody();
        longTitle.put("title", "t".repeat(121));
        assertInvalid(longTitle, "title");

        ObjectNode blank = validBody();
        blank.put("title", "   ");
        assertInvalid(blank, "title");

        ObjectNode missing = validBody();
        missing.remove("title");
        assertInvalid(missing, "title");
    }

    @Test
    void rejectsMessagesThatAreTooLongOrBlank() throws Exception {
        ObjectNode longMessage = validBody();
        longMessage.put("message", "m".repeat(501));
        assertInvalid(longMessage, "message");

        ObjectNode blank = validBody();
        blank.put("message", "");
        assertInvalid(blank, "message");

        ObjectNode missing = validBody();
        missing.remove("message");
        assertInvalid(missing, "message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "", "1234", "11111111-1111-1111-1111-11111111111",
            "11111111-1111-1111-1111-1111111111111", "g1111111-1111-1111-1111-111111111111",
            "11111111111111111111111111111111", "{11111111-1111-1111-1111-111111111111}", "AAAAAAAAAAAAAAAAAAAAAA=="})
    void rejectsMalformedUuids(String value) throws Exception {
        for (String field : new String[] {"targetUserId", "actorUserId", "entityId"}) {
            ObjectNode body = validBody();
            body.put(field, value);
            assertInvalid(body, field);
        }
    }

    @Test
    void entityIdIsRequired() throws Exception {
        ObjectNode missing = validBody();
        missing.remove("entityId");
        assertInvalid(missing, "entityId");
        ObjectNode nullId = validBody();
        nullId.putNull("entityId");
        assertInvalid(nullId, "entityId");
    }

    @Test
    void reportsEveryInvalidFieldAtOnce() throws Exception {
        ObjectNode body = validBody();
        body.put("title", "t".repeat(121));
        body.put("message", "m".repeat(501));
        body.put("entityId", "nope");
        send(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(3));
    }

    @Test
    void rejectsMalformedAndMistypedJson() throws Exception {
        long before = countNotifications();
        for (String json : new String[] {"{", "not json", "[]", "\"text\"", "", "{\"type\": 5}",
                "{\"title\": {\"a\": 1}}"}) {
            mvc.perform(request(TestTokens.INTERNAL_KEY, json)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(countNotifications()).isEqualTo(before);
    }

    @Test
    void rejectsOtherContentTypes() throws Exception {
        mvc.perform(post("/internal/notifications").header(KEY_HEADER, TestTokens.INTERNAL_KEY)
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void validationErrorsDoNotLeakInternals() throws Exception {
        ObjectNode body = validBody();
        body.put("type", "bogus");
        String response = send(body).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("Exception", "org.springframework", "com.microinv", "java.");
    }
}
