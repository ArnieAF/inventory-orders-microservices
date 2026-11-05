package com.microinv.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusTest {

    /** Contrato 6.3, escrito a mano: las 25 combinaciones se comparan contra esta tabla. */
    private static final Map<OrderStatus, Set<OrderStatus>> VALID = Map.of(
            OrderStatus.PENDING, Set.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
            OrderStatus.CONFIRMED, Set.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
            OrderStatus.SHIPPED, Set.of(OrderStatus.DELIVERED),
            OrderStatus.DELIVERED, Set.of(),
            OrderStatus.CANCELLED, Set.of());

    static Stream<Arguments> matrix() {
        Stream.Builder<Arguments> all = Stream.builder();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                all.add(Arguments.of(from, to, VALID.get(from).contains(to)));
            }
        }
        return all.build();
    }

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @MethodSource("matrix")
    void fullTransitionMatrix(OrderStatus from, OrderStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }

    @Test
    void nullTargetIsNeverValid() {
        for (OrderStatus from : OrderStatus.values()) {
            assertThat(from.canTransitionTo(null)).isFalse();
        }
    }

    @Test
    void deliveredAndCancelledAreTerminal() {
        assertThat(OrderStatus.DELIVERED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(OrderStatus.PENDING.isTerminal()).isFalse();
        assertThat(OrderStatus.CONFIRMED.isTerminal()).isFalse();
        assertThat(OrderStatus.SHIPPED.isTerminal()).isFalse();
    }

    @Test
    void thereAreExactlyTheFiveContractStatuses() {
        assertThat(OrderStatus.values()).extracting(Enum::name)
                .containsExactly("PENDING", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED");
    }
}
