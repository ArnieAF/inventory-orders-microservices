package com.microinv.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockOrderingTest {

    @Test
    void ordersAscendingByTextualUuidWhichMatchesUnsignedByteOrder() {
        // Con signo (UUID.compareTo) 8000... iria ANTES que 7fff...; en PostgreSQL (bytes sin signo) va DESPUES.
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        UUID low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(StockOrdering.ascending(List.of(high, low, first))).containsExactly(first, low, high);
        assertThat(high.compareTo(low)).isNegative(); // demuestra por que no se usa UUID.compareTo
    }

    @Test
    void orderIsIndependentOfInputOrderAndRemovesDuplicates() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(UUID.randomUUID());
        }
        List<UUID> expected = StockOrdering.ascending(ids);
        for (int i = 0; i < 5; i++) {
            List<UUID> shuffled = new ArrayList<>(ids);
            shuffled.add(ids.get(0)); // duplicado
            Collections.shuffle(shuffled);
            assertThat(StockOrdering.ascending(shuffled)).isEqualTo(expected);
        }
        assertThat(expected).hasSize(20).isSortedAccordingTo(StockOrdering.ASCENDING);
    }
}
