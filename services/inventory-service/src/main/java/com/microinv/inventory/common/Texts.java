package com.microinv.inventory.common;

import java.util.Locale;

/** Utilidades puras de texto: SKU normalizado y patrones LIKE seguros. */
public final class Texts {

    public static final char LIKE_ESCAPE = '\\';

    private Texts() {
    }

    /** SKU guardado en mayusculas y sin espacios en los extremos (contrato 6.2). */
    public static String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }

    /** Texto libre: recorta y convierte la cadena vacia en {@code null}. */
    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Patron "contiene" en minusculas para {@code LIKE ... ESCAPE '\'}: los comodines del usuario
     * ({@code %}, {@code _}) y el propio caracter de escape se tratan como texto literal.
     */
    public static String containsPattern(String term) {
        StringBuilder sb = new StringBuilder("%");
        for (char c : term.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                sb.append(LIKE_ESCAPE);
            }
            sb.append(c);
        }
        return sb.append('%').toString();
    }
}
