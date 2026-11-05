package com.microinv.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microinv.orders.client.NotificationRequest;
import com.microinv.orders.client.NotificationType;
import com.microinv.orders.domain.OrderStatus;
import com.microinv.orders.error.InventoryUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

class OrderStatusApiTest extends AbstractApiTest {

    /** Matriz del contrato escrita a mano e independiente de la implementacion (seccion 6.3). */
    private static final Map<OrderStatus, Set<OrderStatus>> VALID = Map.of(
            OrderStatus.PENDING, Set.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
            OrderStatus.CONFIRMED, Set.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
            OrderStatus.SHIPPED, Set.of(OrderStatus.DELIVERED),
            OrderStatus.DELIVERED, Set.of(),
            OrderStatus.CANCELLED, Set.of());

    static Stream<Arguments> allTransitions() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                cases.add(Arguments.of(from, to, VALID.get(from).contains(to)));
            }
        }
        return cases.build();
    }

    private static String statusBody(OrderStatus status) {
        return "{\"status\":\"" + status + "\"}";
    }

    @ParameterizedTest(name = "{0} -> {1} valid={2}")
    @MethodSource("allTransitions")
    void transitionMatrix(OrderStatus from, OrderStatus to, boolean valid) throws Exception {
        UUID creator = UUID.fromString("55555555-5555-4555-8555-555555555555");
        UUID orderId = seedOrder(from, creator, "bob", "ACME", new java.math.BigDecimal("10.00"), 1,
                java.time.Instant.parse("2026-01-01T00:00:00Z"));
        UUID actor = UUID.fromString("66666666-6666-4666-8666-666666666666");

        var request = authed(patch("/api/orders/" + orderId + "/status"), actor, "dave")
                .contentType(MediaType.APPLICATION_JSON).content(statusBody(to));

        if (valid) {
            AtomicReference<String> statusSeenByRelease = new AtomicReference<>();
            doAnswer(invocation -> {
                statusSeenByRelease.set(statusInDb(orderId));
                return null;
            }).when(inventory).release(any());

            mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(orderId.toString()))
                    .andExpect(jsonPath("$.status").value(to.name()))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.createdBy").value(creator.toString()));

            assertThat(statusInDb(orderId)).isEqualTo(to.name());
            assertThat(versionInDb(orderId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select updated_at > created_at from orders where id = ?", Boolean.class,
                    orderId)).isTrue();

            if (to == OrderStatus.CANCELLED) {
                verify(inventory, times(1)).release(orderId);
                // la liberacion ocurre ANTES de confirmar el nuevo estado
                assertThat(statusSeenByRelease.get()).isEqualTo(from.name());
            } else {
                verify(inventory, never()).release(any());
            }

            ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
            verify(notifications, times(1)).send(sent.capture());
            NotificationRequest notification = sent.getValue();
            assertThat(notification.type()).isEqualTo(NotificationType.ORDER_STATUS_CHANGED);
            assertThat(notification.targetUserId()).isEqualTo(creator);
            assertThat(notification.actorUserId()).isEqualTo(actor);
            assertThat(notification.entityType()).isEqualTo("ORDER");
            assertThat(notification.entityId()).isEqualTo(orderId);
            assertThat(notification.message()).contains(from.name()).contains(to.name());
            assertThat(notification.message()).doesNotContain("ACME");
        } else {
            mvc.perform(request)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.path").value("/api/orders/" + orderId + "/status"));

            assertThat(statusInDb(orderId)).isEqualTo(from.name());
            assertThat(versionInDb(orderId)).isZero();
            verify(inventory, never()).release(any()); // se valida ANTES de tocar inventario
            verifyNoInteractions(notifications);
        }
    }

    @Test
    void cancellingFailsWith503AndLeavesOrderUntouchedWhenReleaseFails() throws Exception {
        UUID orderId = seedOrder(OrderStatus.CONFIRMED);
        doThrow(new InventoryUnavailableException(true, new IllegalStateException("inventory-service:8082 refused")))
                .when(inventory).release(any());

        mvc.perform(authed(patch("/api/orders/" + orderId + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody(OrderStatus.CANCELLED)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("inventory-service"))));

        assertThat(statusInDb(orderId)).isEqualTo("CONFIRMED");
        assertThat(versionInDb(orderId)).isZero();
        verifyNoInteractions(notifications);
    }

    @Test
    void cancellationCanBeRetriedAfterAFailedRelease() throws Exception {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        doThrow(new InventoryUnavailableException(true, null)).doNothing().when(inventory).release(any());

        var cancel = authed(patch("/api/orders/" + orderId + "/status")).contentType(MediaType.APPLICATION_JSON)
                .content(statusBody(OrderStatus.CANCELLED));
        mvc.perform(cancel).andExpect(status().isServiceUnavailable());
        assertThat(statusInDb(orderId)).isEqualTo("PENDING");

        mvc.perform(authed(patch("/api/orders/" + orderId + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody(OrderStatus.CANCELLED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(statusInDb(orderId)).isEqualTo("CANCELLED");
        verify(inventory, times(2)).release(orderId);
    }

    @Test
    void walksTheWholeHappyPath() throws Exception {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        for (OrderStatus next : List.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.DELIVERED)) {
            mvc.perform(authed(patch("/api/orders/" + orderId + "/status")).contentType(MediaType.APPLICATION_JSON)
                            .content(statusBody(next)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(next.name()));
        }
        assertThat(versionInDb(orderId)).isEqualTo(3L);
        verify(inventory, never()).release(any());
        verify(notifications, times(3)).send(any());
    }

    @Test
    void failingNotificationNeverBreaksAStatusChange() throws Exception {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        doThrow(new IllegalStateException("notification-service down")).when(notifications).send(any());

        mvc.perform(authed(patch("/api/orders/" + orderId + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody(OrderStatus.CONFIRMED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(statusInDb(orderId)).isEqualTo("CONFIRMED");
        verify(notifications, times(1)).send(any());
    }

    @Test
    void unknownOrderIs404() throws Exception {
        mvc.perform(authed(patch("/api/orders/" + UUID.randomUUID() + "/status"))
                        .contentType(MediaType.APPLICATION_JSON).content(statusBody(OrderStatus.CONFIRMED)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(inventory, never()).release(any());
        verifyNoInteractions(notifications);
    }

    @Test
    void malformedOrderIdIs400() throws Exception {
        mvc.perform(authed(patch("/api/orders/not-a-uuid/status")).contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody(OrderStatus.CONFIRMED)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":null}", "{\"status\":\"SHIPPING\"}", "{\"status\":\"pending\"}",
            "{\"status\":5}", "", "[]", "{\"status\":"})
    void invalidStatusBodiesAre400AndChangeNothing(String body) throws Exception {
        UUID orderId = seedOrder(OrderStatus.PENDING);
        mvc.perform(authed(patch("/api/orders/" + orderId + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(statusInDb(orderId)).isEqualTo("PENDING");
        verify(inventory, never()).release(any());
        verifyNoInteractions(notifications);
    }
}
