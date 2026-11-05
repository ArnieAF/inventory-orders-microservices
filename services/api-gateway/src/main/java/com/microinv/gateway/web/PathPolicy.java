package com.microinv.gateway.web;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Politica de rutas del gateway (logica pura, sin Spring Web):
 * <ul>
 *   <li>solo los prefijos del contrato (seccion 4) y {@code /actuator/health} se atienden; el
 *       resto (incluido {@code /internal/**}) es 404 y nunca se enruta;</li>
 *   <li>se rechazan rutas "raras" (segmentos {@code ..}, barras o puntos codificados, backslash,
 *       parametros {@code ;}, caracteres de control) que podrian colarse hacia {@code /internal/**}
 *       en el backend por diferencias de normalizacion.</li>
 * </ul>
 */
public final class PathPolicy {

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final List<PathPattern> ALLOWED = List.of(
            PARSER.parse("/api/auth/**"),
            PARSER.parse("/api/products/**"),
            PARSER.parse("/api/orders/**"),
            PARSER.parse("/api/notifications/**"),
            PARSER.parse("/ws/**"),
            PARSER.parse("/actuator/health"));

    private static final Pattern ENCODED_CONTROL = Pattern.compile("%(0[0-9a-f]|1[0-9a-f]|7f)");

    private PathPolicy() {
    }

    /** {@code true} si la ruta pertenece a la superficie publica del contrato. */
    public static boolean isAllowed(PathContainer path) {
        for (PathPattern pattern : ALLOWED) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /** {@code rawPath} es la ruta tal como llego (sin decodificar). */
    public static boolean isSuspicious(String rawPath) {
        if (rawPath == null) {
            return true;
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")
                || lower.contains("\\") || lower.contains(";") || lower.contains("//")
                || ENCODED_CONTROL.matcher(lower).find()) {
            return true;
        }
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) < 0x20 || lower.charAt(i) == 0x7f) {
                return true;
            }
        }
        for (String segment : lower.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
