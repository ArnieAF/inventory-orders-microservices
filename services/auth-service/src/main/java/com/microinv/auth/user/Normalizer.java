package com.microinv.auth.user;

import java.util.Locale;

/** Normalizacion de identificadores: se guardan y se buscan siempre en minusculas. */
public final class Normalizer {

    private Normalizer() {
    }

    /** Minusculas independientes del locale (evita el problema de la "i" turca). */
    public static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
