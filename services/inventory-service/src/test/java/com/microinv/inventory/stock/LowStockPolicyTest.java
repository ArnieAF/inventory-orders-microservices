package com.microinv.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LowStockPolicyTest {

    @ParameterizedTest(name = "antes={0} despues={1} umbral={2} -> {3}")
    @CsvSource({
            "10, 5, 5, true",   // cruza justo hasta el umbral
            "10, 4, 5, true",   // cruza por debajo
            "6, 5, 5, true",
            "1, 0, 0, true",    // umbral 0: aviso al agotarse
            "5, 4, 5, false",   // ya estaba en el umbral: no se repite
            "3, 2, 5, false",   // ya estaba bajo
            "10, 6, 5, false",  // sigue por encima
            "4, 9, 5, false",   // sube el stock
            "0, 0, 0, false",
            "7, 7, 5, false"
    })
    void crossingRule(int before, int after, int threshold, boolean expected) {
        assertThat(LowStockPolicy.crossedBelowThreshold(before, after, threshold)).isEqualTo(expected);
    }

    @Test
    void raisingStockNeverNotifies() {
        assertThat(LowStockPolicy.crossedBelowThreshold(0, 100, 5)).isFalse();
    }
}
