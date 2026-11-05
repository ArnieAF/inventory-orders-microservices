package com.microinv.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.notifications.support.IntegrationTestBase;
import com.microinv.notifications.support.TestTokens;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** API REST de historial y lectura por usuario (§6.4): visibilidad, "leída" por usuario, paginación, errores. */
class NotificationApiTest extends IntegrationTestBase {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ObjectMapper objectMapper;

    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    private ResultActions list(UUID user, String query) throws Exception {
        return mvc.perform(get("/api/notifications" + query).header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private List<String> ids(ResultActions result) throws Exception {
        JsonNode content = objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .get("content");
        List<String> ids = new ArrayList<>();
        content.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions markRead(UUID user, UUID notificationId) throws Exception {
        return mvc.perform(post("/api/notifications/" + notificationId + "/read")
                .header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private long unreadCount(UUID user) throws Exception {
        return body(mvc.perform(get("/api/notifications/unread-count")
                .header(HttpHeaders.AUTHORIZATION, bearer(user))).andExpect(status().isOk())).get("count").asLong();
    }

    // ---- Visibilidad: difusión vs. dirigida

    @Test
    void broadcastIsVisibleToEveryoneAndTargetedOnlyToItsRecipient() throws Exception {
        UUID broadcast = insert(null, BASE.plusSeconds(1));
        UUID forA = insert(userA, BASE.plusSeconds(2));
        UUID forB = insert(userB, BASE.plusSeconds(3));
        UUID forNobodyInParticular = insert(UUID.randomUUID(), BASE.plusSeconds(4));

        List<String> seenByA = ids(list(userA, "").andExpect(status().isOk()));
        List<String> seenByB = ids(list(userB, "").andExpect(status().isOk()));

        assertThat(seenByA).containsExactlyInAnyOrder(broadcast.toString(), forA.toString());
        assertThat(seenByB).containsExactlyInAnyOrder(broadcast.toString(), forB.toString());
        assertThat(seenByA).doesNotContain(forB.toString(), forNobodyInParticular.toString());
        assertThat(seenByB).doesNotContain(forA.toString(), forNobodyInParticular.toString());

        list(userA, "").andExpect(jsonPath("$.totalElements").value(2));
        assertThat(unreadCount(userA)).isEqualTo(2);
        assertThat(unreadCount(userB)).isEqualTo(2);
    }

    @Test
    void notificationJsonMatchesTheContract() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into notifications (id, type, title, message, target_user_id, actor_user_id, "
                        + "entity_type, entity_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, "LOW_STOCK", "Low stock", "SKU-1 is running low", userA, actor, "PRODUCT", entity,
                java.time.OffsetDateTime.parse("2026-02-03T04:05:06.789Z"));

        list(userA, "").andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].type").value("LOW_STOCK"))
                .andExpect(jsonPath("$.content[0].title").value("Low stock"))
                .andExpect(jsonPath("$.content[0].message").value("SKU-1 is running low"))
                .andExpect(jsonPath("$.content[0].targetUserId").value(userA.toString()))
                .andExpect(jsonPath("$.content[0].actorUserId").value(actor.toString()))
                .andExpect(jsonPath("$.content[0].entityType").value("PRODUCT"))
                .andExpect(jsonPath("$.content[0].entityId").value(entity.toString()))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-02-03T04:05:06.789Z"))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void nullTargetAndActorAreSerializedAsNull() throws Exception {
        insert(null, BASE);
        String json = list(userA, "").andReturn().getResponse().getContentAsString();
        JsonNode first = objectMapper.readTree(json).get("content").get(0);
        assertThat(first.has("targetUserId")).isTrue();
        assertThat(first.get("targetUserId").isNull()).isTrue();
        assertThat(first.has("actorUserId")).isTrue();
        assertThat(first.get("actorUserId").isNull()).isTrue();
    }

    // ---- "leída" por usuario

    @Test
    void readFlagIsPerUserEvenForABroadcast() throws Exception {
        UUID broadcast = insert(null, BASE.plusSeconds(1));
        insert(userA, BASE.plusSeconds(2));

        markRead(userA, broadcast).andExpect(status().isNoContent()).andExpect(content().string(""));

        // Para A: la difusión figura como leída; para B sigue sin leer.
        list(userA, "").andExpect(jsonPath("$.content[?(@.id == '" + broadcast + "')].read").value(true));
        list(userB, "").andExpect(jsonPath("$.content[?(@.id == '" + broadcast + "')].read").value(false));
        assertThat(unreadCount(userA)).isEqualTo(1);
        assertThat(unreadCount(userB)).isEqualTo(1);
        assertThat(countReads(userA)).isEqualTo(1);
        assertThat(countReads(userB)).isZero();
    }

    @Test
    void markReadIsIdempotent() throws Exception {
        UUID broadcast = insert(null, BASE);
        markRead(userA, broadcast).andExpect(status().isNoContent());
        markRead(userA, broadcast).andExpect(status().isNoContent());
        markRead(userA, broadcast).andExpect(status().isNoContent());
        assertThat(countReads(userA)).isEqualTo(1);
        assertThat(unreadCount(userA)).isZero();
    }

    @Test
    void markReadOnATargetedNotificationOfAnotherUserIsNotFoundAndChangesNothing() throws Exception {
        UUID forB = insert(userB, BASE);

        markRead(userA, forB).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/notifications/" + forB + "/read"));

        assertThat(countReads(userA)).isZero();
        assertThat(countReads(userB)).isZero();
        assertThat(unreadCount(userB)).isEqualTo(1);
        // Y el propietario sí puede.
        markRead(userB, forB).andExpect(status().isNoContent());
        assertThat(unreadCount(userB)).isZero();
    }

    @Test
    void markReadOnAnUnknownNotificationIsNotFound() throws Exception {
        markRead(userA, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void markReadWithAMalformedIdIsAValidationError() throws Exception {
        mvc.perform(post("/api/notifications/not-a-uuid/read").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void readAllMarksOnlyWhatTheUserCanSee() throws Exception {
        UUID broadcast = insert(null, BASE.plusSeconds(1));
        UUID forA = insert(userA, BASE.plusSeconds(2));
        UUID forB = insert(userB, BASE.plusSeconds(3));

        mvc.perform(post("/api/notifications/read-all").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isNoContent()).andExpect(content().string(""));

        assertThat(unreadCount(userA)).isZero();
        // Lo dirigido a B y la difusión de B siguen sin leer.
        assertThat(unreadCount(userB)).isEqualTo(2);
        assertThat(countReads(userA)).isEqualTo(2);
        assertThat(countReads(userB)).isZero();
        Integer foreignRows = jdbc.queryForObject(
                "select count(*) from notification_reads where user_id = ? and notification_id = ?",
                Integer.class, userA, forB);
        assertThat(foreignRows).isZero();
        list(userB, "").andExpect(jsonPath("$.content[?(@.id == '" + forB + "')].read").value(false))
                .andExpect(jsonPath("$.content[?(@.id == '" + broadcast + "')].read").value(false));
        list(userA, "").andExpect(jsonPath("$.content[?(@.id == '" + forA + "')].read").value(true));
    }

    @Test
    void readAllIsIdempotentAndHandlesNewNotificationsAfterwards() throws Exception {
        insert(null, BASE);
        mvc.perform(post("/api/notifications/read-all").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/notifications/read-all").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isNoContent());
        assertThat(countReads(userA)).isEqualTo(1);

        insert(userA, BASE.plusSeconds(5));
        assertThat(unreadCount(userA)).isEqualTo(1);
        mvc.perform(post("/api/notifications/read-all").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isNoContent());
        assertThat(unreadCount(userA)).isZero();
        assertThat(countReads(userA)).isEqualTo(2);
    }

    @Test
    void readAllWorksWithMoreNotificationsThanOneInternalBatch() throws Exception {
        for (int i = 0; i < 1100; i++) {
            insert(i % 2 == 0 ? null : userA, BASE.plusSeconds(i));
        }
        insert(userB, BASE.plusSeconds(5000));
        assertThat(unreadCount(userA)).isEqualTo(1100);
        mvc.perform(post("/api/notifications/read-all").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isNoContent());
        assertThat(unreadCount(userA)).isZero();
        assertThat(countReads(userA)).isEqualTo(1100);
        assertThat(unreadCount(userB)).isEqualTo(551);
    }

    @Test
    void concurrentMarkReadOfTheSameNotificationAlwaysSucceedsWithASingleRow() throws Exception {
        UUID broadcast = insert(null, BASE);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> markRead(userA, broadcast).andReturn().getResponse().getStatus());
            }
            List<Future<Integer>> results = pool.invokeAll(tasks);
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(204);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(countReads(userA)).isEqualTo(1);
    }

    // ---- unreadOnly

    @Test
    void unreadOnlyFiltersOutWhatTheUserAlreadyRead() throws Exception {
        UUID first = insert(null, BASE.plusSeconds(1));
        UUID second = insert(null, BASE.plusSeconds(2));
        UUID third = insert(userA, BASE.plusSeconds(3));
        markRead(userA, second).andExpect(status().isNoContent());

        assertThat(ids(list(userA, "?unreadOnly=true"))).containsExactly(third.toString(), first.toString());
        list(userA, "?unreadOnly=true").andExpect(jsonPath("$.totalElements").value(2));
        // B no leyó nada: ve las dos difusiones.
        assertThat(ids(list(userB, "?unreadOnly=true"))).containsExactly(second.toString(), first.toString());
        assertThat(ids(list(userA, "?unreadOnly=false"))).hasSize(3);
    }

    @Test
    void unreadOnlyPaginatesOverTheFilteredSet() throws Exception {
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            all.add(insert(null, BASE.plusSeconds(i)));
        }
        for (int i = 0; i < 12; i += 2) {
            markRead(userA, all.get(i)).andExpect(status().isNoContent());
        }
        list(userA, "?unreadOnly=true&page=0&size=4").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
        list(userA, "?unreadOnly=true&page=1&size=4")
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void emptyParametersFallBackToTheDefaults() throws Exception {
        // El contrato escribe "?unreadOnly=&page=&size=".
        insert(null, BASE);
        list(userA, "?unreadOnly=&page=&size=").andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ---- Paginación, orden y límites (§5)

    @Test
    void paginatesNewestFirst() throws Exception {
        List<UUID> inserted = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            inserted.add(insert(null, BASE.plusSeconds(i)));
        }

        ResultActions firstPage = list(userA, "?page=0&size=10").andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3));
        List<String> page0 = ids(firstPage);
        assertThat(page0).hasSize(10);
        assertThat(page0.get(0)).isEqualTo(inserted.get(24).toString());
        assertThat(page0.get(9)).isEqualTo(inserted.get(15).toString());

        List<String> page2 = ids(list(userA, "?page=2&size=10").andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2)));
        assertThat(page2).hasSize(5);
        assertThat(page2.get(4)).isEqualTo(inserted.get(0).toString());

        // Fuera de rango: vacío pero válido.
        list(userA, "?page=9&size=10").andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(25));
    }

    @Test
    void sortByCreatedAtAscendingIsAllowed() throws Exception {
        UUID oldest = insert(null, BASE);
        insert(null, BASE.plusSeconds(1));
        UUID newest = insert(null, BASE.plusSeconds(2));
        assertThat(ids(list(userA, "?sort=createdAt,asc"))).startsWith(oldest.toString()).endsWith(newest.toString());
        assertThat(ids(list(userA, "?sort=createdAt,desc"))).startsWith(newest.toString()).endsWith(oldest.toString());
    }

    @Test
    void sameTimestampStillPaginatesWithoutDuplicates() throws Exception {
        for (int i = 0; i < 7; i++) {
            insert(null, BASE);
        }
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            seen.addAll(ids(list(userA, "?page=" + page + "&size=3")));
        }
        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
    }

    @Test
    void sizeAboveTheLimitIsRejected() throws Exception {
        list(userA, "?size=101").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("size"))
                .andExpect(jsonPath("$.path").value("/api/notifications"));
        list(userA, "?size=100000").andExpect(status().isBadRequest());
        list(userA, "?size=0").andExpect(status().isBadRequest());
        list(userA, "?size=-1").andExpect(status().isBadRequest());
        list(userA, "?page=-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("page"));
        list(userA, "?size=100").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
        list(userA, "?size=1").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1));
    }

    @Test
    void invalidSortAndTypesAreValidationErrors() throws Exception {
        list(userA, "?sort=title,asc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("sort"));
        list(userA, "?sort=createdAt,sideways").andExpect(status().isBadRequest());
        list(userA, "?page=abc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        list(userA, "?size=1.5").andExpect(status().isBadRequest());
        list(userA, "?unreadOnly=maybe").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void errorsNeverLeakInternals() throws Exception {
        MvcResult result = list(userA, "?size=abc").andExpect(status().isBadRequest()).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "org.springframework", "java.lang", "at com.", "SELECT",
                "select ", "hibernate");
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.fieldNames()).toIterable()
                .contains("timestamp", "status", "code", "message", "path")
                .doesNotContain("trace", "exception", "error");
        assertThat(Instant.parse(json.get("timestamp").asText())).isBefore(Instant.now().plusSeconds(5));
    }

    // ---- Autenticación

    @Test
    void requestsWithoutAValidJwtAreUnauthorized() throws Exception {
        UUID id = insert(null, BASE);
        String[] tokens = {
                TestTokens.expired(userA),
                TestTokens.signedWithOtherSecret(userA),
                TestTokens.wrongIssuer(userA),
                TestTokens.unsigned(userA),
                TestTokens.hs512(userA),
                TestTokens.tampered(userA, userB),
                TestTokens.withSubject("not-a-uuid"),
                TestTokens.withoutExpiration(userA),
                "garbage"};
        for (String token : tokens) {
            mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.status").value(401));
        }
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/notifications"));
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/" + id + "/read")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
        assertThat(countReads(userA)).isZero();
    }

    @Test
    void theTokenIsNotAcceptedFromTheQueryString() throws Exception {
        mvc.perform(get("/api/notifications").queryParam("access_token", TestTokens.valid(userA)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theUserComesFromTheTokenNeverFromClientHeaders() throws Exception {
        UUID forB = insert(userB, BASE);
        // Un cliente malicioso intenta suplantar a B con headers: se ignoran.
        mvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userA))
                        .header("X-User-Id", userB.toString())
                        .header("X-Username", "b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/notifications/" + forB + "/read")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userA))
                        .header("X-User-Id", userB.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthIsPublicAndDoesNotExposeDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void otherActuatorEndpointsAreNotAvailable() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/heapdump").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownRoutesAreNotFoundInTheCommonFormat() throws Exception {
        mvc.perform(get("/api/nothing-here").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/nothing-here"));
        mvc.perform(get("/api/nothing-here")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongMethodsAreRejectedInTheCommonFormat() throws Exception {
        mvc.perform(post("/api/notifications").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void internalRoutesAreNotReachableThroughTheJwtChain() throws Exception {
        // Un JWT válido jamás sirve para /internal/** (cadena aparte, solo X-Internal-Key).
        mvc.perform(get("/internal/notifications").header(HttpHeaders.AUTHORIZATION, bearer(userA)))
                .andExpect(status().isUnauthorized());
    }
}
