package com.microinv.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microinv.notifications.api.dto.NotificationResponse;
import com.microinv.notifications.domain.EntityType;
import com.microinv.notifications.domain.NotificationType;
import com.microinv.notifications.service.NotificationPublisher;
import com.microinv.notifications.support.IntegrationTestBase;
import com.microinv.notifications.support.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Qué se emite y cuándo: después del commit, con el contenido correcto, y un fallo al emitir no rompe el POST
 * interno (la notificación ya está persistida).
 */
@ExtendWith(OutputCaptureExtension.class)
class InternalNotificationEmissionTest extends IntegrationTestBase {

    @MockitoBean
    private NotificationPublisher publisher;

    @Autowired
    private ObjectMapper objectMapper;

    private ObjectNode body(String targetUserId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "LOW_STOCK");
        body.put("title", "Low stock");
        body.put("message", "Product SKU-1 is running low");
        if (targetUserId == null) {
            body.putNull("targetUserId");
        } else {
            body.put("targetUserId", targetUserId);
        }
        body.put("entityType", "PRODUCT");
        body.put("entityId", UUID.randomUUID().toString());
        return body;
    }

    private MockHttpServletRequestBuilder request(Object body, String key) throws Exception {
        return post("/internal/notifications").header("X-Internal-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private long count() {
        Long count = jdbc.queryForObject("select count(*) from notifications", Long.class);
        return count == null ? 0 : count;
    }

    @Test
    void publishesTheStoredNotificationOnceAsUnread() throws Exception {
        UUID target = UUID.randomUUID();
        ObjectNode body = body(target.toString());

        mvc.perform(request(body, TestTokens.INTERNAL_KEY)).andExpect(status().isCreated());

        ArgumentCaptor<NotificationResponse> captor = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(publisher, times(1)).publish(captor.capture());
        NotificationResponse published = captor.getValue();
        assertThat(published.type()).isEqualTo(NotificationType.LOW_STOCK);
        assertThat(published.entityType()).isEqualTo(EntityType.PRODUCT);
        assertThat(published.targetUserId()).isEqualTo(target);
        assertThat(published.read()).isFalse();
        assertThat(published.entityId().toString()).isEqualTo(body.get("entityId").asText());
        Integer stored = jdbc.queryForObject("select count(*) from notifications where id = ?", Integer.class,
                published.id());
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void publishesABroadcastWithoutTarget() throws Exception {
        mvc.perform(request(body(null), TestTokens.INTERNAL_KEY)).andExpect(status().isCreated());
        ArgumentCaptor<NotificationResponse> captor = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().targetUserId()).isNull();
    }

    @Test
    void emitsOnlyAfterTheTransactionIsCommitted() throws Exception {
        List<Long> rowsSeenFromAnotherConnection = new ArrayList<>();
        AtomicReference<UUID> publishedId = new AtomicReference<>();
        doAnswer(invocation -> {
            NotificationResponse published = invocation.getArgument(0);
            publishedId.set(published.id());
            // jdbc usa otra conexión: solo ve datos ya confirmados.
            rowsSeenFromAnotherConnection.add(jdbc.queryForObject(
                    "select count(*) from notifications where id = ?", Long.class, published.id()));
            return null;
        }).when(publisher).publish(any());

        mvc.perform(request(body(null), TestTokens.INTERNAL_KEY)).andExpect(status().isCreated());

        assertThat(publishedId.get()).isNotNull();
        assertThat(rowsSeenFromAnotherConnection).containsExactly(1L);
    }

    @Test
    void aFailureWhileEmittingDoesNotBreakTheRequestAndTheNotificationIsKept(CapturedOutput output)
            throws Exception {
        doThrow(new IllegalStateException("broker is down")).when(publisher).publish(any());

        String response = mvc.perform(request(body(null), TestTokens.INTERNAL_KEY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.read").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(count()).isEqualTo(1);
        // Nada del fallo interno llega al llamante...
        assertThat(response).doesNotContain("broker is down", "Exception");
        // ...pero se registra.
        assertThat(output.getAll()).contains("could not be emitted over WebSocket").contains("broker is down");
    }

    @Test
    void messagingFailuresAreAlsoContained() throws Exception {
        doThrow(new MessageDeliveryException("Failed to send message")).when(publisher).publish(any());
        mvc.perform(request(body(UUID.randomUUID().toString()), TestTokens.INTERNAL_KEY))
                .andExpect(status().isCreated());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void nothingIsPublishedWhenTheRequestIsRejected() throws Exception {
        mvc.perform(post("/internal/notifications").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body(null)))).andExpect(status().isUnauthorized());
        mvc.perform(request(body(null), "wrong-key")).andExpect(status().isUnauthorized());
        ObjectNode invalid = body(null);
        invalid.put("type", "NOPE");
        mvc.perform(request(invalid, TestTokens.INTERNAL_KEY)).andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
        assertThat(count()).isZero();
    }

    @Test
    void logsNeverContainTheKeysOrTokens(CapturedOutput output) throws Exception {
        String attacker = "attacker-guess-key-123";
        mvc.perform(request(body(null), attacker)).andExpect(status().isUnauthorized());
        String token = TestTokens.expired(UUID.randomUUID());
        mvc.perform(post("/api/notifications/read-all").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(body(null), TestTokens.INTERNAL_KEY)).andExpect(status().isCreated());

        assertThat(output.getAll())
                .doesNotContain(attacker)
                .doesNotContain(token)
                .doesNotContain(TestTokens.INTERNAL_KEY)
                .doesNotContain(TestTokens.SECRET);
    }
}
