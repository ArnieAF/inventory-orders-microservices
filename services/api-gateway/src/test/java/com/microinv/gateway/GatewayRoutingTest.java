package com.microinv.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Contrato del gateway (secciones 3 y 4) con backends simulados: rutas publicas y protegidas, 401 en
 * formato comun, cabeceras internas eliminadas, /internal y todo lo ajeno al contrato -> 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRoutingTest {

    static final StubServer AUTH = StubServer.http("auth");
    static final StubServer INVENTORY = StubServer.http("inventory");
    static final StubServer ORDER = StubServer.http("order");
    static final StubServer NOTIFICATION = StubServer.http("notification");
    static final StubServer NOTIFICATION_WS = StubServer.websocket("notification-ws");
    static final List<StubServer> ALL = List.of(AUTH, INVENTORY, ORDER, NOTIFICATION, NOTIFICATION_WS);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ROUTE_AUTH_URI", AUTH::httpUri);
        registry.add("ROUTE_INVENTORY_URI", INVENTORY::httpUri);
        registry.add("ROUTE_ORDER_URI", ORDER::httpUri);
        registry.add("ROUTE_NOTIFICATION_URI", NOTIFICATION::httpUri);
        registry.add("ROUTE_NOTIFICATION_WS_URI", NOTIFICATION_WS::wsUri);
        registry.add("JWT_SECRET", () -> TestJwt.SECRET);
    }

    @AfterAll
    static void stopStubs() {
        ALL.forEach(StubServer::close);
    }

    @Autowired WebTestClient client;
    @Autowired HttpClientProperties httpClientProperties;
    @LocalServerPort int port;

    @BeforeEach
    void resetStubs() {
        ALL.forEach(StubServer::reset);
    }

    private static String bearer() {
        return "Bearer " + TestJwt.valid(UUID.randomUUID().toString(), "alice");
    }

    private void assertNothingReachedAnyBackend() {
        for (StubServer stub : ALL) {
            assertThat(stub.received).as("peticiones recibidas por %s", stub.name).isEmpty();
        }
    }

    private void assertUnauthorized(WebTestClient.ResponseSpec response, String path) {
        response.expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").isNotEmpty()
                .jsonPath("$.path").isEqualTo(path)
                .jsonPath("$.timestamp").isNotEmpty()
                .jsonPath("$.details").doesNotExist();
    }

    // ---------- configuracion ----------

    @Test
    void timeoutsDelContrato_conexion2s_respuesta10s() {
        assertThat(httpClientProperties.getConnectTimeout()).isEqualTo(2000);
        assertThat(httpClientProperties.getResponseTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    // ---------- rutas publicas ----------

    @Test
    void registerYLogin_sonPublicos_yLlegaAlAuthService() {
        client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.backend").isEqualTo("auth").jsonPath("$.uri").isEqualTo("/api/auth/register");
        client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"password\":\"x\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.backend").isEqualTo("auth").jsonPath("$.uri").isEqualTo("/api/auth/login");

        assertThat(AUTH.received).hasSize(2);
        assertThat(AUTH.received.get(0).method()).isEqualTo("POST");
        assertThat(AUTH.received.get(0).body()).isEqualTo("{\"username\":\"alice\"}"); // cuerpo intacto
        assertThat(INVENTORY.received).isEmpty();
    }

    @Test
    void registerYLogin_ignoranUnAuthorizationInvalidoOCaducado() {
        String stale = "Bearer " + TestJwt.expired(UUID.randomUUID().toString(), "alice");
        client.post().uri("/api/auth/login").header(HttpHeaders.AUTHORIZATION, stale)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk();
        client.post().uri("/api/auth/register").header(HttpHeaders.AUTHORIZATION, "Bearer basura")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk();
        assertThat(AUTH.received).hasSize(2);
    }

    @Test
    void health_esPublico_sinDetalles_yLoResuelveElGateway() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"status\":\"UP\"}", true);
        assertNothingReachedAnyBackend();
    }

    // ---------- rutas protegidas: 401 ----------

    @Test
    void rutasProtegidas_sinToken_401_yNoLlegaNadaAlBackend() {
        assertUnauthorized(client.get().uri("/api/products").exchange(), "/api/products");
        assertUnauthorized(client.get().uri("/api/products/summary").exchange(), "/api/products/summary");
        assertUnauthorized(client.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange(), "/api/orders");
        assertUnauthorized(client.get().uri("/api/notifications/unread-count").exchange(),
                "/api/notifications/unread-count");
        assertUnauthorized(client.get().uri("/api/auth/me").exchange(), "/api/auth/me");
        assertNothingReachedAnyBackend();
    }

    @Test
    void metodoDistintoEnRutaPublica_noEsPublico() {
        // Solo POST register/login son publicos.
        assertUnauthorized(client.get().uri("/api/auth/login").exchange(), "/api/auth/login");
        assertUnauthorized(client.get().uri("/api/auth/register").exchange(), "/api/auth/register");
        assertUnauthorized(client.put().uri("/api/auth/login").exchange(), "/api/auth/login");
        assertNothingReachedAnyBackend();
    }

    @Test
    void tokenInvalido_caducado_manipulado_ofirmaAjena_401() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String valid = TestJwt.valid(id, "alice");
        List<String> badTokens = List.of(
                "basura",
                "no.es.un.jwt",
                TestJwt.expired(id, "alice"),
                TestJwt.tamperPayload(valid, "\"username\":\"alice\"", "\"username\":\"admin\""),
                valid.substring(0, valid.length() - 4),
                TestJwt.mint("another-secret-another-secret-another-secret", TestJwt.ISSUER, id, "alice", now,
                        now.plusSeconds(600)),
                TestJwt.mint(TestJwt.SECRET, "evil-issuer", id, "alice", now, now.plusSeconds(600)),
                TestJwt.mint(TestJwt.SECRET, null, id, "alice", now, now.plusSeconds(600)),
                TestJwt.mint(TestJwt.SECRET, TestJwt.ISSUER, null, "alice", now, now.plusSeconds(600)),
                TestJwt.unsigned(id, "alice"));
        for (String bad : badTokens) {
            assertUnauthorized(client.get().uri("/api/products")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bad).exchange(), "/api/products");
        }
        // Esquema distinto de Bearer
        assertUnauthorized(client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Basic YWxpY2U6cHc=").exchange(), "/api/orders");
        assertNothingReachedAnyBackend();
    }

    @Test
    void tokenEnQueryString_noSeAcepta() {
        String token = TestJwt.valid(UUID.randomUUID().toString(), "alice");
        assertUnauthorized(client.get().uri("/api/products?access_token=" + token).exchange(), "/api/products");
        assertNothingReachedAnyBackend();
    }

    @Test
    void lasCabecerasDeIdentidadDelClienteNoSustituyenAlToken() {
        assertUnauthorized(client.get().uri("/api/products")
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-Username", "admin")
                .header("X-Internal-Key", "secreto").exchange(), "/api/products");
        assertNothingReachedAnyBackend();
    }

    // ---------- enrutado con token valido ----------

    @Test
    void conTokenValido_cadaPrefijoVaASuServicio_sinReescribirElPath() {
        String auth = bearer();
        record Case(HttpMethod method, String uri, StubServer expected) {
        }
        List<Case> cases = List.of(
                new Case(HttpMethod.GET, "/api/auth/me", AUTH),
                new Case(HttpMethod.GET, "/api/products", INVENTORY),
                new Case(HttpMethod.GET, "/api/products/summary", INVENTORY),
                new Case(HttpMethod.GET, "/api/products/" + UUID.randomUUID(), INVENTORY),
                new Case(HttpMethod.POST, "/api/products/" + UUID.randomUUID() + "/stock-adjustments", INVENTORY),
                new Case(HttpMethod.PUT, "/api/products/" + UUID.randomUUID(), INVENTORY),
                new Case(HttpMethod.DELETE, "/api/products/" + UUID.randomUUID(), INVENTORY),
                new Case(HttpMethod.POST, "/api/orders", ORDER),
                new Case(HttpMethod.GET, "/api/orders/summary", ORDER),
                new Case(HttpMethod.PATCH, "/api/orders/" + UUID.randomUUID() + "/status", ORDER),
                new Case(HttpMethod.GET, "/api/notifications?unreadOnly=true&page=0&size=20", NOTIFICATION),
                new Case(HttpMethod.GET, "/api/notifications/unread-count", NOTIFICATION),
                new Case(HttpMethod.POST, "/api/notifications/read-all", NOTIFICATION));
        for (Case c : cases) {
            ALL.forEach(StubServer::reset);
            client.method(c.method()).uri(c.uri()).header(HttpHeaders.AUTHORIZATION, auth)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"k\":\"v\"}")
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.backend").isEqualTo(c.expected().name);

            assertThat(c.expected().received).as("%s %s", c.method(), c.uri()).hasSize(1);
            StubServer.Received r = c.expected().received.get(0);
            assertThat(r.method()).isEqualTo(c.method().name());
            assertThat(r.uri()).as("path y query sin reescritura").isEqualTo(c.uri());
            assertThat(r.header("authorization")).as("el JWT se reenvia: cada servicio lo revalida").isEqualTo(auth);
            for (StubServer other : ALL) {
                if (other != c.expected()) {
                    assertThat(other.received).as("%s no debia recibir %s", other.name, c.uri()).isEmpty();
                }
            }
        }
    }

    @Test
    void elBodyYElStatusDelBackendSePropaganSinCambios() {
        String conflict = "{\"timestamp\":\"2026-11-05T12:00:00Z\",\"status\":409,\"code\":\"INSUFFICIENT_STOCK\","
                + "\"message\":\"x\",\"path\":\"/api/orders\",\"details\":[{\"field\":\"productId\",\"message\":\"m\"}]}";
        client.post().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer())
                .header("x-stub-status", "409").header("x-stub-body", conflict)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().json(conflict, true);
    }

    // ---------- cabeceras internas ----------

    @Test
    void seEliminanXInternalKey_XUserId_XUsername_antesDeReenviar() {
        client.get().uri("/api/products").header(HttpHeaders.AUTHORIZATION, bearer())
                .header("X-Internal-Key", "clave-falsa").header("x-user-id", "otro-usuario")
                .header("X-USERNAME", "admin").header("X-Custom", "se-conserva")
                .exchange().expectStatus().isOk();

        StubServer.Received r = INVENTORY.received.get(0);
        assertThat(r.hasHeader("x-internal-key")).isFalse();
        assertThat(r.hasHeader("x-user-id")).isFalse();
        assertThat(r.hasHeader("x-username")).isFalse();
        assertThat(r.header("x-custom")).isEqualTo("se-conserva");
        assertThat(r.hasHeader("authorization")).isTrue();
    }

    @Test
    void tambienSeEliminanEnRutasPublicas() {
        client.post().uri("/api/auth/login").header("X-Internal-Key", "k").header("X-User-Id", "u")
                .header("X-Username", "n").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk();

        StubServer.Received r = AUTH.received.get(0);
        assertThat(r.hasHeader("x-internal-key")).isFalse();
        assertThat(r.hasHeader("x-user-id")).isFalse();
        assertThat(r.hasHeader("x-username")).isFalse();
    }

    @Test
    void cabecerasInternasRepetidas_seEliminanTodas() {
        client.get().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer())
                .header("X-Internal-Key", "a", "b").header("X-User-Id", "1", "2")
                .exchange().expectStatus().isOk();
        StubServer.Received r = ORDER.received.get(0);
        assertThat(r.hasHeader("x-internal-key")).isFalse();
        assertThat(r.hasHeader("x-user-id")).isFalse();
    }

    // ---------- /internal y rutas ajenas al contrato -> 404 ----------

    private void assertNotFound(WebTestClient.ResponseSpec response, String path) {
        response.expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.code").isEqualTo("NOT_FOUND")
                .jsonPath("$.message").isNotEmpty()
                .jsonPath("$.path").isEqualTo(path)
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void internal_nuncaSeEnruta_404_conOSinToken_yConLaClaveInterna() {
        List<String> paths = List.of("/internal/stock/reserve", "/internal/stock/release", "/internal/notifications",
                "/internal", "/internal/");
        for (String path : paths) {
            assertNotFound(client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange(), path);
            assertNotFound(client.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer())
                    .header("X-Internal-Key", "cualquiera").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange(), path);
        }
        assertNothingReachedAnyBackend();
    }

    @Test
    void rutasFueraDelContrato_404_conOSinToken() {
        List<String> paths = List.of("/", "/foo", "/api", "/api/", "/api/unknown", "/api/authx/login",
                "/api/productsX", "/api/orders-old", "/API/PRODUCTS", "/Api/Auth/login", "/actuator", "/actuator/env",
                "/actuator/info", "/actuator/metrics", "/actuator/beans", "/actuator/gateway/routes",
                "/actuator/health/liveness", "/ws2", "/wsx", "/error", "/favicon.ico");
        for (String path : paths) {
            assertNotFound(client.get().uri(path).exchange(), path);
            assertNotFound(client.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer()).exchange(), path);
        }
        assertNothingReachedAnyBackend();
    }

    @Test
    void rutasConTrucosDeNormalizacion_404_yNoLlegaNadaAlBackend() throws Exception {
        String token = "Authorization: " + bearer();
        List<String> paths = List.of(
                "/api/products/../../internal/stock/reserve",
                "/api/products/./../../internal/stock/reserve",
                "/api/products/%2e%2e/%2e%2e/internal/stock/reserve",
                "/api/products/%2E%2E/x",
                "/api/products/..%2finternal/stock/reserve",
                "/api/products/%2e./x",
                "/api/products%2f..%2f..%2finternal",
                "/api/products/..%5cinternal",
                "/api/products;x=1/../../internal/x",
                "/api/products/a;b",
                "/api/orders//x",
                "//internal/stock/reserve",
                "/api/products/%00",
                "/api/products/%0d%0aX-Injected:1");
        for (String path : paths) {
            RawHttp.Response res = RawHttp.get(port, path, token);
            assertThat(res.status()).as("GET %s", path).isEqualTo(404);
            assertThat(res.body()).contains("\"code\":\"NOT_FOUND\"");
        }
        assertNothingReachedAnyBackend();
    }

    // ---------- WebSocket / CORS / cabeceras de seguridad ----------

    @Test
    void getAWs_sinUpgrade_404() {
        assertNotFound(client.get().uri("/ws").exchange(), "/ws");
        assertNotFound(client.get().uri("/ws").header(HttpHeaders.AUTHORIZATION, bearer()).exchange(), "/ws");
        assertNothingReachedAnyBackend();
    }

    @Test
    void sinCors_laPeticionPreflightNoRecibeCabecerasAccessControl() {
        client.options().uri("/api/products")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
    }

    @Test
    void respuestasProxiadas_llevanCabecerasDeSeguridad_ySinCookies() {
        client.get().uri("/api/products").header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().doesNotExist("Set-Cookie")
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
