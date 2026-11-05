package com.microinv.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Destino caido o lento -> 503 SERVICE_UNAVAILABLE en el formato comun (nunca un 500 con detalles). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Timeout de respuesta corto solo para que la prueba no tarde 10 s.
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s"})
@AutoConfigureWebTestClient(timeout = "20s")
class UpstreamFailureTest {

    static final int CLOSED_PORT = freePort();
    static final StubServer SLOW = StubServer.http("slow");
    static final StubServer HEALTHY = StubServer.http("healthy");

    static {
        SLOW.setDelay(Duration.ofSeconds(4));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ROUTE_INVENTORY_URI", () -> "http://127.0.0.1:" + CLOSED_PORT); // nadie escucha
        registry.add("ROUTE_ORDER_URI", SLOW::httpUri);
        registry.add("ROUTE_AUTH_URI", HEALTHY::httpUri);
        registry.add("JWT_SECRET", () -> TestJwt.SECRET);
    }

    @AfterAll
    static void stop() {
        SLOW.close();
        HEALTHY.close();
    }

    @Autowired WebTestClient client;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bearer() {
        return "Bearer " + TestJwt.valid(UUID.randomUUID().toString(), "alice");
    }

    @Test
    void destinoCaido_503_SERVICE_UNAVAILABLE_enFormatoComun() {
        client.get().uri("/api/products").header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Service temporarily unavailable")
                .jsonPath("$.path").isEqualTo("/api/products")
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void destinoLento_superaElTimeoutDeRespuesta_503_SERVICE_UNAVAILABLE() {
        long start = System.nanoTime();
        client.get().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.status").isEqualTo(503);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).as("responde al vencer el timeout, sin esperar al backend lento").isLessThan(3_500);
    }

    @Test
    void laCaidaDeUnServicio_noAfectaALosDemas() {
        client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk();
    }

    @Test
    void elMensajeDeErrorNoRevelaHostsNiPuertosInternos() {
        String body = new String(client.get().uri("/api/products").header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange().expectBody().returnResult().getResponseBody());
        assertThat(body).doesNotContain("127.0.0.1").doesNotContain(String.valueOf(CLOSED_PORT))
                .doesNotContain("Connection refused").doesNotContain("Exception");
    }
}
