package com.microinv.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.orders.client.ReserveResult;
import com.microinv.orders.client.ReservedItem;
import com.microinv.orders.domain.OrderLine;
import com.microinv.orders.dto.ItemRequest;
import com.microinv.orders.error.ApiException;
import com.microinv.orders.error.InventoryUnavailableException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderLinesTest {

    private static final UUID P1 = UUID.randomUUID();
    private static final UUID P2 = UUID.randomUUID();

    private static ReservedItem reserved(UUID id, String price, int quantity) {
        return new ReservedItem(id, "SKU-" + id.toString().substring(0, 4), "Name", new BigDecimal(price), quantity);
    }

    // ---- items repetidos --------------------------------------------------------------------------------------

    @Test
    void acceptsDistinctProducts() {
        OrderLines.rejectDuplicates(List.of(new ItemRequest(P1, 1), new ItemRequest(P2, 1)));
    }

    @Test
    void rejectsRepeatedProductReportingEveryRepeatedIndex() {
        List<ItemRequest> items = List.of(new ItemRequest(P1, 1), new ItemRequest(P2, 1), new ItemRequest(P1, 2),
                new ItemRequest(P2, 3));
        assertThatThrownBy(() -> OrderLines.rejectDuplicates(items))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(400);
                    assertThat(e.code().name()).isEqualTo("VALIDATION_ERROR");
                    assertThat(e.details()).extracting(d -> d.get("field"))
                            .containsExactly("items[2].productId", "items[3].productId");
                });
    }

    // ---- instantanea de inventario ----------------------------------------------------------------------------

    @Test
    void usesPriceNameAndSkuFromInventoryInRequestOrder() {
        List<OrderLine> lines = OrderLines.fromReservation(
                List.of(new ItemRequest(P1, 2), new ItemRequest(P2, 5)),
                new ReserveResult(List.of(reserved(P2, "3.5", 5), reserved(P1, "10", 2))));

        assertThat(lines).extracting(OrderLine::productId).containsExactly(P1, P2);
        assertThat(lines.get(0).unitPrice()).isEqualTo(new BigDecimal("10.00"));
        assertThat(lines.get(1).unitPrice()).isEqualTo(new BigDecimal("3.50"));
        assertThat(lines.get(0).unitPrice().scale()).isEqualTo(2);
        assertThat(lines.get(0).quantity()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1000000.00", "1.005", "-5"})
    void rejectsOutOfRangeOrOverPreciseInventoryPrices(String price) {
        assertThatThrownBy(() -> OrderLines.fromReservation(List.of(new ItemRequest(P1, 1)),
                new ReserveResult(List.of(reserved(P1, price, 1)))))
                .isInstanceOf(InventoryUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "999999.99", "12.500", "7"})
    void acceptsBoundaryInventoryPrices(String price) {
        List<OrderLine> lines = OrderLines.fromReservation(List.of(new ItemRequest(P1, 1)),
                new ReserveResult(List.of(reserved(P1, price, 1))));
        assertThat(lines.get(0).unitPrice().scale()).isEqualTo(2);
    }

    @Test
    void rejectsIncoherentInventoryResponses() {
        List<ItemRequest> requested = List.of(new ItemRequest(P1, 2), new ItemRequest(P2, 1));

        // null / sin items
        assertThatThrownBy(() -> OrderLines.fromReservation(requested, null))
                .isInstanceOf(InventoryUnavailableException.class);
        assertThatThrownBy(() -> OrderLines.fromReservation(requested, new ReserveResult(null)))
                .isInstanceOf(InventoryUnavailableException.class);
        // faltan items
        assertThatThrownBy(() -> OrderLines.fromReservation(requested,
                new ReserveResult(List.of(reserved(P1, "1", 2)))))
                .isInstanceOf(InventoryUnavailableException.class);
        // sobra un item desconocido (mismo tamano, distinto producto)
        assertThatThrownBy(() -> OrderLines.fromReservation(requested,
                new ReserveResult(List.of(reserved(P1, "1", 2), reserved(UUID.randomUUID(), "1", 1)))))
                .isInstanceOf(InventoryUnavailableException.class);
        // producto repetido en la respuesta
        assertThatThrownBy(() -> OrderLines.fromReservation(requested,
                new ReserveResult(List.of(reserved(P1, "1", 2), reserved(P1, "1", 2)))))
                .isInstanceOf(InventoryUnavailableException.class);
        // cantidad distinta de la pedida
        assertThatThrownBy(() -> OrderLines.fromReservation(requested,
                new ReserveResult(List.of(reserved(P1, "1", 3), reserved(P2, "1", 1)))))
                .isInstanceOf(InventoryUnavailableException.class);
        // elemento nulo
        List<ReservedItem> withNull = new ArrayList<>();
        withNull.add(reserved(P1, "1", 2));
        withNull.add(null);
        assertThatThrownBy(() -> OrderLines.fromReservation(requested, new ReserveResult(withNull)))
                .isInstanceOf(InventoryUnavailableException.class);
    }

    @Test
    void rejectsMissingOrOversizedTextAndMissingPriceOrQuantity() {
        List<ItemRequest> requested = List.of(new ItemRequest(P1, 1));
        assertThat(bad(requested, new ReservedItem(P1, null, "Name", BigDecimal.ONE, 1))).isTrue();
        assertThat(bad(requested, new ReservedItem(P1, " ", "Name", BigDecimal.ONE, 1))).isTrue();
        assertThat(bad(requested, new ReservedItem(P1, "S".repeat(41), "Name", BigDecimal.ONE, 1))).isTrue();
        assertThat(bad(requested, new ReservedItem(P1, "SKU", null, BigDecimal.ONE, 1))).isTrue();
        assertThat(bad(requested, new ReservedItem(P1, "SKU", "N".repeat(121), BigDecimal.ONE, 1))).isTrue();
        assertThat(bad(requested, new ReservedItem(P1, "SKU", "Name", null, 1))).isTrue();
        assertThat(bad(requested, new ReservedItem(P1, "SKU", "Name", BigDecimal.ONE, null))).isTrue();
        assertThat(bad(requested, new ReservedItem(null, "SKU", "Name", BigDecimal.ONE, 1))).isTrue();
        // y los limites exactos si pasan
        assertThat(bad(requested, new ReservedItem(P1, "S".repeat(40), "N".repeat(120), BigDecimal.ONE, 1))).isFalse();
    }

    private static boolean bad(List<ItemRequest> requested, ReservedItem item) {
        try {
            OrderLines.fromReservation(requested, new ReserveResult(List.of(item)));
            return false;
        } catch (InventoryUnavailableException e) {
            return true;
        }
    }

    @Test
    void apiExceptionDetailsAreAnImmutableSnapshot() {
        List<Map<String, Object>> details = new ArrayList<>(List.of(Map.of("field", "x", "message", "y")));
        ApiException e = ApiException.validation("m", details);
        details.clear();
        assertThat(e.details()).hasSize(1);
        assertThatThrownBy(() -> e.details().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
