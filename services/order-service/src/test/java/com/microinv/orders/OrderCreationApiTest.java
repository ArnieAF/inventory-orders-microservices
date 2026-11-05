package com.microinv.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.microinv.orders.client.NotificationRequest;
import com.microinv.orders.client.NotificationType;
import com.microinv.orders.client.ReserveItem;
import com.microinv.orders.client.ReserveResult;
import com.microinv.orders.client.ReservedItem;
import com.microinv.orders.error.ErrorCode;
import com.microinv.orders.error.InventoryRejectedException;
import com.microinv.orders.error.InventoryUnavailableException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class OrderCreationApiTest extends AbstractApiTest {

    private static final UUID SOME_PRODUCT = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static String item(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }

    private static String order(String customer, String items) {
        return "{\"customerName\":\"" + customer + "\",\"items\":[" + items + "]}";
    }

    // ---- caso feliz -------------------------------------------------------------------------------------------

    @Test
    void createsPendingOrderWithTotalFromInventoryPricesAndNotifiesAfterCommit() throws Exception {
        Product widget = Product.of("WID-1", "Widget", "10.50");
        Product gadget = Product.of("GAD-1", "Gadget", "3.00");
        stubReserveWith(widget, gadget);
        AtomicReference<Boolean> committedWhenNotified = new AtomicReference<>();
        doAnswer(invocation -> {
            NotificationRequest sent = invocation.getArgument(0);
            committedWhenNotified.set(orderCount() == 1 && sent.entityId() != null);
            return null;
        }).when(notifications).send(any());

        String body = "{\"customerName\":\"  ACME Corp \",\"notes\":\"Leave at the door\",\"items\":["
                + item(widget.id(), 2) + "," + item(gadget.id(), 3) + "]}";
        MvcResult result = mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.customerName").value("ACME Corp"))
                .andExpect(jsonPath("$.notes").value("Leave at the door"))
                .andExpect(content().string(containsString("\"total\":30.00")))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.sku=='WID-1')].productName").value("Widget"))
                .andExpect(content().string(containsString("\"unitPrice\":10.50")))
                .andExpect(content().string(containsString("\"lineTotal\":21.00")))
                .andExpect(content().string(containsString("\"lineTotal\":9.00")))
                .andExpect(jsonPath("$.createdBy").value(USER_ID.toString()))
                .andExpect(jsonPath("$.createdByUsername").value(USERNAME))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andReturn();

        JsonNode created = json.readTree(result.getResponse().getContentAsString());
        UUID orderId = UUID.fromString(created.get("id").asText());
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/orders/" + orderId);

        // reserva con orderRef = id del pedido, y solo productId/quantity
        ArgumentCaptor<UUID> ref = ArgumentCaptor.forClass(UUID.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReserveItem>> items = ArgumentCaptor.forClass(List.class);
        verify(inventory).reserve(ref.capture(), items.capture());
        assertThat(ref.getValue()).isEqualTo(orderId);
        assertThat(items.getValue()).containsExactlyInAnyOrder(new ReserveItem(widget.id(), 2),
                new ReserveItem(gadget.id(), 3));
        verify(inventory, never()).release(any());

        // guardado de verdad
        assertThat(orderCount()).isEqualTo(1);
        assertThat(statusInDb(orderId)).isEqualTo("PENDING");

        // NEW_ORDER broadcast, despues del commit
        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(1)).send(sent.capture());
        NotificationRequest notification = sent.getValue();
        assertThat(notification.type()).isEqualTo(NotificationType.NEW_ORDER);
        assertThat(notification.targetUserId()).isNull();
        assertThat(notification.actorUserId()).isEqualTo(USER_ID);
        assertThat(notification.entityType()).isEqualTo("ORDER");
        assertThat(notification.entityId()).isEqualTo(orderId);
        assertThat(notification.title().length()).isLessThanOrEqualTo(120);
        assertThat(notification.message().length()).isLessThanOrEqualTo(500);
        assertThat(notification.message()).doesNotContain("ACME").doesNotContain("door");
        assertThat(committedWhenNotified.get()).isTrue();
    }

    @Test
    void totalIsExactDecimalArithmeticWithTwoDecimals() throws Exception {
        Product a = Product.of("A", "A", "0.10");
        Product b = Product.of("B", "B", "19.99");
        stubReserveWith(a, b);
        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("X", item(a.id(), 3) + "," + item(b.id(), 7))))
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"total\":140.23"))) // 0.30 + 139.93
                .andExpect(content().string(containsString("\"unitPrice\":0.10")));
        BigDecimal stored = jdbc.queryForObject("select total from orders", BigDecimal.class);
        assertThat(stored).isEqualByComparingTo("140.23");
    }

    @Test
    void priceComesFromInventoryEvenIfBodyCarriesAnother() throws Exception {
        Product widget = Product.of("WID-1", "Widget", "10.50");
        stubReserveWith(widget);
        String body = "{\"customerName\":\"ACME\",\"total\":0.01,\"unitPrice\":0.01,\"items\":[{\"productId\":\""
                + widget.id() + "\",\"quantity\":2,\"unitPrice\":0.01,\"price\":0.01,\"lineTotal\":0.02,"
                + "\"sku\":\"EVIL\",\"productName\":\"Evil\"}]}";
        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"total\":21.00")))
                .andExpect(content().string(containsString("\"unitPrice\":10.50")))
                .andExpect(jsonPath("$.items[0].sku").value("WID-1"))
                .andExpect(jsonPath("$.items[0].productName").value("Widget"));
    }

    @Test
    void createdByComesFromTokenNeverFromBodyOrHeaders() throws Exception {
        Product widget = Product.of("WID-1", "Widget", "1.00");
        stubReserveWith(widget);
        UUID tokenUser = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID spoofed = UUID.fromString("44444444-4444-4444-8444-444444444444");
        String body = "{\"customerName\":\"ACME\",\"createdBy\":\"" + spoofed + "\",\"createdByUsername\":\"mallory\","
                + "\"items\":[" + item(widget.id(), 1) + "]}";

        mvc.perform(authed(post("/api/orders"), tokenUser, "carol")
                        .header("X-User-Id", spoofed.toString())
                        .header("X-Username", "mallory")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(tokenUser.toString()))
                .andExpect(jsonPath("$.createdByUsername").value("carol"));

        assertThat(jdbc.queryForObject("select created_by from orders", UUID.class)).isEqualTo(tokenUser);
        assertThat(jdbc.queryForObject("select created_by_username from orders", String.class)).isEqualTo("carol");
        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).send(sent.capture());
        assertThat(sent.getValue().actorUserId()).isEqualTo(tokenUser);
    }

    @Test
    void storesSnapshotThatLaterInventoryChangesCannotAlter() throws Exception {
        Product widget = Product.of("WID-1", "Widget", "10.00");
        stubReserveWith(widget);
        MvcResult result = mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("ACME", item(widget.id(), 1))))
                .andExpect(status().isCreated()).andReturn();
        // El pedido guarda sku, nombre y precio propios: no hay referencia viva al producto.
        assertThat(jdbc.queryForObject("select count(*) from order_items where sku = 'WID-1' and product_name = "
                + "'Widget' and unit_price = 10.00", Long.class)).isEqualTo(1L);
        assertThat(result.getResponse().getContentAsString()).contains("WID-1");
    }

    // ---- errores de inventario --------------------------------------------------------------------------------

    @Test
    void propagatesInsufficientStockWithDetailsAndStoresNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        when(inventory.reserve(any(), anyList())).thenThrow(new InventoryRejectedException(HttpStatus.CONFLICT,
                ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock for one or more products",
                List.of(Map.of("productId", productId.toString(), "requested", 5, "available", 2))));

        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("ACME", item(productId, 5))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.details[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.details[0].requested").value(5))
                .andExpect(jsonPath("$.details[0].available").value(2));

        assertThat(orderCount()).isZero();
        verify(inventory, never()).release(any());
        verifyNoInteractions(notifications);
    }

    @Test
    void propagatesNotFoundFromInventoryAndStoresNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        // Forma real de inventory (contrato 6.2): details:[{productId, message}]
        when(inventory.reserve(any(), anyList())).thenThrow(new InventoryRejectedException(HttpStatus.NOT_FOUND,
                ErrorCode.NOT_FOUND, "One or more products were not found",
                List.of(Map.of("productId", productId.toString(), "message", "Product not found"))));

        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("ACME", item(productId, 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.details[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.details[0].message").value("Product not found"));

        assertThat(orderCount()).isZero();
        verifyNoInteractions(notifications);
    }

    @Test
    void propagatesProductInactiveFromInventoryAndStoresNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        when(inventory.reserve(any(), anyList())).thenThrow(new InventoryRejectedException(HttpStatus.CONFLICT,
                ErrorCode.PRODUCT_INACTIVE, "One or more products are inactive",
                List.of(Map.of("productId", productId.toString(), "message", "Product is inactive"))));

        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("ACME", item(productId, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"))
                .andExpect(jsonPath("$.details[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.details[0].message").value("Product is inactive"));

        assertThat(orderCount()).isZero();
        verifyNoInteractions(notifications);
    }

    @Test
    void inventoryDownGives503WithoutInternalDetailsAndNoOrder() throws Exception {
        when(inventory.reserve(any(), anyList())).thenThrow(new InventoryUnavailableException(false,
                new IllegalStateException("Connection refused: inventory-service:8082 secret-detail")));

        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("ACME", item(SOME_PRODUCT, 1))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(content().string(not(containsString("inventory-service"))))
                .andExpect(content().string(not(containsString("secret-detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));

        assertThat(orderCount()).isZero();
        verify(inventory, never()).release(any()); // la peticion ni siquiera llego: nada que compensar
        verifyNoInteractions(notifications);
    }

    @Test
    void unknownReserveOutcomeTriggersBestEffortRelease() throws Exception {
        when(inventory.reserve(any(), anyList())).thenThrow(new InventoryUnavailableException(true, null));

        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("ACME", item(SOME_PRODUCT, 1))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        ArgumentCaptor<UUID> ref = ArgumentCaptor.forClass(UUID.class);
        verify(inventory).reserve(ref.capture(), any());
        verify(inventory).release(ref.getValue());
        assertThat(orderCount()).isZero();
    }

    @Test
    void unusableInventoryResponseGives503AndReleasesStock() throws Exception {
        UUID productId = UUID.randomUUID();
        // precio ausente: respuesta incoherente
        when(inventory.reserve(any(), anyList())).thenReturn(new ReserveResult(
                List.of(new ReservedItem(productId, "SKU", "Name", null, 1))));

        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("ACME", item(productId, 1))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        ArgumentCaptor<UUID> ref = ArgumentCaptor.forClass(UUID.class);
        verify(inventory).reserve(ref.capture(), any());
        verify(inventory).release(ref.getValue());
        assertThat(orderCount()).isZero();
        verifyNoInteractions(notifications);
    }

    // ---- fallo al guardar: compensacion -----------------------------------------------------------------------

    @Test
    void failureWhileSavingReleasesStockAndReturnsGenericError() throws Exception {
        Product widget = Product.of("WID-1", "Widget", "10.00");
        stubReserveWith(widget);

        // Fallo REAL de BD: sin la tabla order_items el INSERT falla y la transaccion se revierte.
        jdbc.execute("alter table order_items rename to order_items_tmp");
        try {
            mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                            .content(order("ACME", item(widget.id(), 1))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("Unexpected error"))
                    .andExpect(content().string(not(containsString("order_items"))))
                    .andExpect(content().string(not(containsString("SQL"))))
                    .andExpect(content().string(not(containsString("Exception"))));
        } finally {
            jdbc.execute("alter table order_items_tmp rename to order_items");
        }

        ArgumentCaptor<UUID> ref = ArgumentCaptor.forClass(UUID.class);
        verify(inventory).reserve(ref.capture(), any());
        verify(inventory).release(ref.getValue()); // compensacion
        assertThat(orderCount()).isZero();
        verifyNoInteractions(notifications);
    }

    @Test
    void failureWhileSavingStillGivesGenericErrorWhenCompensationAlsoFails() throws Exception {
        Product widget = Product.of("WID-1", "Widget", "10.00");
        stubReserveWith(widget);
        doThrow(new InventoryUnavailableException(true, null)).when(inventory).release(any());

        jdbc.execute("alter table order_items rename to order_items_tmp");
        try {
            mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                            .content(order("ACME", item(widget.id(), 1))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        } finally {
            jdbc.execute("alter table order_items_tmp rename to order_items");
        }
        verify(inventory, times(1)).release(any());
        assertThat(orderCount()).isZero();
    }

    // ---- validacion -------------------------------------------------------------------------------------------

    static Stream<Arguments> invalidBodies() {
        String ok = item(SOME_PRODUCT, 1);
        Stream.Builder<Arguments> cases = Stream.builder();
        cases.add(Arguments.of("customerName empty", order("", ok)));
        cases.add(Arguments.of("customerName blank", order("   ", ok)));
        cases.add(Arguments.of("customerName missing", "{\"items\":[" + ok + "]}"));
        cases.add(Arguments.of("customerName 121 chars", order("a".repeat(121), ok)));
        cases.add(Arguments.of("customerName with control char", order("a\\u0000b", ok)));
        cases.add(Arguments.of("notes 501 chars",
                "{\"customerName\":\"X\",\"notes\":\"" + "n".repeat(501) + "\",\"items\":[" + ok + "]}"));
        cases.add(Arguments.of("notes with NUL", "{\"customerName\":\"X\",\"notes\":\"a\\u0000b\",\"items\":[" + ok
                + "]}"));
        cases.add(Arguments.of("items missing", "{\"customerName\":\"X\"}"));
        cases.add(Arguments.of("items null", "{\"customerName\":\"X\",\"items\":null}"));
        cases.add(Arguments.of("items empty", order("X", "")));
        cases.add(Arguments.of("items element null", order("X", "null")));
        cases.add(Arguments.of("items not an array", "{\"customerName\":\"X\",\"items\":" + ok + "}"));
        cases.add(Arguments.of("quantity 0", order("X", item(SOME_PRODUCT, 0))));
        cases.add(Arguments.of("quantity negative", order("X", item(SOME_PRODUCT, -3))));
        cases.add(Arguments.of("quantity 10001", order("X", item(SOME_PRODUCT, 10001))));
        cases.add(Arguments.of("quantity missing", order("X", "{\"productId\":\"" + SOME_PRODUCT + "\"}")));
        cases.add(Arguments.of("quantity as string", order("X", "{\"productId\":\"" + SOME_PRODUCT
                + "\",\"quantity\":\"5\"}")));
        cases.add(Arguments.of("quantity decimal", order("X", "{\"productId\":\"" + SOME_PRODUCT
                + "\",\"quantity\":1.5}")));
        cases.add(Arguments.of("productId missing", order("X", "{\"quantity\":1}")));
        cases.add(Arguments.of("productId not a uuid", order("X", "{\"productId\":\"abc\",\"quantity\":1}")));
        cases.add(Arguments.of("malformed json", "{\"customerName\":"));
        cases.add(Arguments.of("not an object", "[]"));
        StringBuilder fiftyOne = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            fiftyOne.append(i > 0 ? "," : "").append(item(UUID.randomUUID(), 1));
        }
        cases.add(Arguments.of("51 items", order("X", fiftyOne.toString())));
        return cases.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void rejectsInvalidBodiesWith400AndDoesNotTouchInventory(String name, String body) throws Exception {
        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(content().string(not(containsString("Exception"))));

        verifyNoInteractions(inventory);
        verifyNoInteractions(notifications);
        assertThat(orderCount()).isZero();
    }

    @Test
    void rejectsRepeatedProductIdWithDetailsAndDoesNotTouchInventory() throws Exception {
        UUID repeated = UUID.randomUUID();
        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("X", item(repeated, 1) + "," + item(UUID.randomUUID(), 1) + ","
                                + item(repeated, 2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("items[2].productId"));
        verifyNoInteractions(inventory);
        assertThat(orderCount()).isZero();
    }

    @Test
    void validationErrorDetailsNameTheOffendingFieldWithoutEchoingValues() throws Exception {
        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("X", item(SOME_PRODUCT, 10001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("items[0].quantity"))
                .andExpect(content().string(not(containsString("10001"))));
    }

    @Test
    void acceptsTheDocumentedLimits() throws Exception {
        // 50 items, cantidad 10000, nombre de 120 y notas de 500 caracteres
        List<Product> catalog = new ArrayList<>();
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            Product product = Product.of("SKU-" + i, "Name " + i, "1.00");
            catalog.add(product);
            items.append(i > 0 ? "," : "").append(item(product.id(), i == 0 ? 10000 : 1));
        }
        stubReserveWith(catalog.toArray(new Product[0]));

        String body = "{\"customerName\":\"" + "c".repeat(120) + "\",\"notes\":\"" + "n".repeat(500)
                + "\",\"items\":[" + items + "]}";
        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(content().string(containsString("\"total\":10049.00")));
    }

    @Test
    void notesAreOptionalAndBlankNotesBecomeNull() throws Exception {
        Product widget = Product.of("WID-1", "Widget", "1.00");
        stubReserveWith(widget);
        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"X\",\"notes\":\"   \",\"items\":[" + item(widget.id(), 1)
                                + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notes").doesNotExist())
                .andExpect(content().string(containsString("\"notes\":null")));
    }

    @Test
    void wrongContentTypeIs415WithValidationErrorCodeLikeTheOtherServices() throws Exception {
        mvc.perform(authed(post("/api/orders")).contentType(MediaType.TEXT_PLAIN)
                        .content(order("X", item(SOME_PRODUCT, 1))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(jsonPath("$.message").value("Unsupported Media Type"));
        verifyNoInteractions(inventory);
    }

    // ---- notificaciones best-effort ---------------------------------------------------------------------------

    @Test
    void notificationFailureNeverBreaksCreation() throws Exception {
        Product widget = Product.of("WID-1", "Widget", "10.00");
        stubReserveWith(widget);
        doThrow(new IllegalStateException("notification-service down")).when(notifications).send(any());

        mvc.perform(authed(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(order("ACME", item(widget.id(), 1))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));

        assertThat(orderCount()).isEqualTo(1);
        verify(inventory, never()).release(any()); // un fallo de notificacion NO compensa el stock
        verify(notifications, times(1)).send(any());
    }
}
