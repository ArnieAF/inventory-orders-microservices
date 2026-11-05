package com.microinv.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderEntityTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private static OrderLine line(String price, int quantity) {
        return new OrderLine(UUID.randomUUID(), "SKU", "Name", new BigDecimal(price), quantity);
    }

    private static OrderEntity order(OrderLine... lines) {
        return OrderEntity.create(UUID.randomUUID(), "ACME", null, UUID.randomUUID(), "alice", List.of(lines), NOW);
    }

    @Test
    void newOrderIsPendingAndTotalIsTheSumOfLineTotalsWithTwoDecimals() {
        OrderEntity order = order(line("10.5", 2), line("0.10", 3), line("19.99", 7));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotal()).isEqualTo(new BigDecimal("161.23")); // 21.00 + 0.30 + 139.93
        assertThat(order.getTotal().scale()).isEqualTo(2);
        assertThat(order.getItems()).extracting(OrderItemEntity::getLineTotal)
                .containsExactly(new BigDecimal("21.00"), new BigDecimal("0.30"), new BigDecimal("139.93"));
        assertThat(order.getItems()).allSatisfy(item -> {
            assertThat(item.getUnitPrice().scale()).isEqualTo(2);
            assertThat(item.getLineTotal().scale()).isEqualTo(2);
        });
        assertThat(order.getCreatedAt()).isEqualTo(NOW);
        assertThat(order.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void zeroPricedLinesGiveZeroTotalWithTwoDecimals() {
        OrderEntity order = order(line("0", 5));
        assertThat(order.getTotal()).isEqualTo(new BigDecimal("0.00"));
        assertThat(order.getTotal().toPlainString()).isEqualTo("0.00");
    }

    @Test
    void largestAllowedOrderFitsTheColumns() {
        // 50 lineas x 10000 unidades x 999999.99 = 499,999,995,000.00 (cabe en NUMERIC(14,2))
        OrderLine[] lines = new OrderLine[50];
        for (int i = 0; i < 50; i++) {
            lines[i] = line("999999.99", 10000);
        }
        OrderEntity order = order(lines);
        assertThat(order.getTotal()).isEqualTo(new BigDecimal("499999995000.00"));
        assertThat(order.getTotal().precision()).isLessThanOrEqualTo(14);
    }

    @Test
    void anOrderWithoutLinesIsRejected() {
        assertThatThrownBy(() -> OrderEntity.create(UUID.randomUUID(), "ACME", null, UUID.randomUUID(), "alice",
                List.of(), NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void priceWithMoreThanTwoSignificantDecimalsIsNotSilentlyRounded() {
        assertThatThrownBy(() -> order(line("1.005", 1))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void changeStatusFollowsTheStateMachineAndTouchesUpdatedAt() {
        OrderEntity order = order(line("1.00", 1));
        Instant later = NOW.plusSeconds(60);

        order.changeStatus(OrderStatus.CONFIRMED, later);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getUpdatedAt()).isEqualTo(later);
        assertThat(order.getCreatedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> order.changeStatus(OrderStatus.DELIVERED, later))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void itemsListIsNotModifiableFromOutside() {
        OrderEntity order = order(line("1.00", 1));
        assertThatThrownBy(() -> order.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
