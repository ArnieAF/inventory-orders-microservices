package com.microinv.inventory.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextsTest {

    @Test
    void skuIsUppercasedAndTrimmed() {
        assertThat(Texts.normalizeSku("  abc-1.x_y ")).isEqualTo("ABC-1.X_Y");
    }

    @Test
    void skuNormalizationIsLocaleIndependent() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR")); // "i" -> "I" con punto
            assertThat(Texts.normalizeSku("item-i")).isEqualTo("ITEM-I");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void blankToNull() {
        assertThat(Texts.blankToNull(null)).isNull();
        assertThat(Texts.blankToNull("   ")).isNull();
        assertThat(Texts.blankToNull("  hola ")).isEqualTo("hola");
    }

    @Test
    void containsPatternEscapesLikeWildcards() {
        assertThat(Texts.containsPattern("AbC")).isEqualTo("%abc%");
        assertThat(Texts.containsPattern("50%_off")).isEqualTo("%50\\%\\_off%");
        assertThat(Texts.containsPattern("a\\b")).isEqualTo("%a\\\\b%");
    }
}
