package com.microinv.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * Sin variables ROUTE_*_URI, los destinos son los nombres DNS de Compose del contrato. Solo hay las
 * 5 rutas de la seccion 4 y ninguna reescribe el path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.jwt.secret=test-only-secret-do-not-use-in-prod-0123456789abcdef")
class DefaultRouteUrisTest {

    @Autowired RouteLocator routeLocator;

    @Test
    void destinosPorDefecto_sonLosDelContrato() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotNull();
        Map<String, URI> uris = routes.stream().collect(Collectors.toMap(Route::getId, Route::getUri));

        assertThat(uris).containsOnlyKeys("auth", "inventory", "orders", "notifications", "notifications-ws");
        assertThat(uris.get("auth")).hasToString("http://auth-service:8081");
        assertThat(uris.get("inventory")).hasToString("http://inventory-service:8082");
        assertThat(uris.get("orders")).hasToString("http://order-service:8083");
        assertThat(uris.get("notifications")).hasToString("http://notification-service:8084");
        assertThat(uris.get("notifications-ws")).hasToString("ws://notification-service:8084");
    }

    @Test
    void ningunaRutaReescribeElPath_niTieneFiltros() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotEmpty();
        for (Route route : routes) {
            assertThat(route.getFilters()).as("filtros de la ruta %s", route.getId()).isEmpty();
        }
    }
}
