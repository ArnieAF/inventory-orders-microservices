package com.microinv.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.microinv.gateway.web.PathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.PathContainer;

/** Logica pura de la politica de rutas (seccion 4 del contrato). */
class PathPolicyTest {

    private static boolean allowed(String path) {
        return PathPolicy.isAllowed(PathContainer.parsePath(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/register", "/api/auth/login", "/api/auth/me", "/api/auth",
            "/api/products", "/api/products/", "/api/products/summary", "/api/products/123/stock-adjustments",
            "/api/orders", "/api/orders/summary", "/api/orders/abc/status",
            "/api/notifications", "/api/notifications/unread-count", "/api/notifications/1/read",
            "/ws", "/ws/", "/ws/abc",
            "/actuator/health"})
    void rutasDelContrato_sePermiten(String path) {
        assertThat(allowed(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "", "/api", "/api/", "/internal", "/internal/stock/reserve", "/internal/notifications",
            "/api/authx", "/api/productsX", "/api/orders-old", "/api/internal/stock/reserve",
            "/API/PRODUCTS", "/actuator", "/actuator/env", "/actuator/health/liveness", "/actuator/health/x",
            "/ws2", "/wsx", "/error", "/h2-console", "/api/auth2/login"})
    void restoDeRutas_noSePermiten(String path) {
        assertThat(allowed(path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/products/../../internal/stock/reserve", "/api/products/./x", "/api/products/..", "/..",
            "/api/products/%2e%2e/x", "/api/products/%2E%2e/x", "/api/products/%2e/x", "/api/products/..%2finternal",
            "/api/products/a%2Fb", "/api/products%2f..", "/api/products/a%5Cb", "/api/products/a\\b",
            "/api/products;jsessionid=1", "/api/products/a;b=c/x", "/api//products", "//api/products", "/api/products//",
            "/api/products/%00", "/api/products/%0d%0a", "/api/products/%1F", "/api/products/%7f",
            "/api/products/a\tb", "/api/products/a\nb"})
    void rutasSospechosas_seRechazan(String rawPath) {
        assertThat(PathPolicy.isSuspicious(rawPath)).as(rawPath).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/products", "/api/products/", "/api/products/123e4567-e89b-12d3-a456-426614174000",
            "/api/products/summary", "/api/products/a.b", "/api/products/a..b", "/api/products/.hidden",
            "/api/products/a%20b", "/api/products/caf%C3%A9", "/api/products/a%2520b", "/ws", "/actuator/health"})
    void rutasNormales_noSonSospechosas(String rawPath) {
        assertThat(PathPolicy.isSuspicious(rawPath)).as(rawPath).isFalse();
    }

    @Test
    void rutaNula_esSospechosa() {
        assertThat(PathPolicy.isSuspicious(null)).isTrue();
    }
}
