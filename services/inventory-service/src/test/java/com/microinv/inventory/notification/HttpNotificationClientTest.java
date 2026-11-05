package com.microinv.inventory.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.inventory.config.NotificationProperties;
import com.microinv.inventory.config.SecurityProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Prueba el cliente HTTP real contra un servidor JDK en el propio proceso (sin red externa ni Docker). */
class HttpNotificationClientTest {

    private static final String KEY = "internal-key-for-tests";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpNotificationClient client(String baseUrl, Duration connect, Duration read) {
        return new HttpNotificationClient(RestClient.builder(),
                new NotificationProperties(baseUrl, connect, read, false),
                new SecurityProperties(SECRET, "micro-inventory", KEY));
    }

    private String start(int status, long delayMs, AtomicReference<String> keyHeader, AtomicReference<String> body,
                         AtomicReference<String> path, CompletableFuture<Void> received) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            keyHeader.set(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
            path.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            try (InputStream in = exchange.getRequestBody()) {
                body.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            received.complete(null);
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void sendsContractBodyWithInternalKey() throws Exception {
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        String url = start(201, 0, key, body, path, new CompletableFuture<>());

        UUID productId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        client(url, Duration.ofSeconds(2), Duration.ofSeconds(5)).send(new NotificationRequest(
                "LOW_STOCK", "Stock bajo", "mensaje", null, actor, "PRODUCT", productId));

        assertThat(path.get()).isEqualTo("POST /internal/notifications");
        assertThat(key.get()).isEqualTo(KEY);
        JsonNode json = new ObjectMapper().readTree(body.get());
        assertThat(json.get("type").asText()).isEqualTo("LOW_STOCK");
        assertThat(json.get("title").asText()).isEqualTo("Stock bajo");
        assertThat(json.get("entityType").asText()).isEqualTo("PRODUCT");
        assertThat(json.get("entityId").asText()).isEqualTo(productId.toString());
        assertThat(json.get("actorUserId").asText()).isEqualTo(actor.toString());
        assertThat(json.has("targetUserId")).isTrue();
        assertThat(json.get("targetUserId").isNull()).isTrue();
    }

    @Test
    void serverErrorIsSwallowed() throws Exception {
        String url = start(500, 0, new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>(),
                new CompletableFuture<>());
        assertThatCode(() -> client(url, Duration.ofSeconds(2), Duration.ofSeconds(5)).send(sample()))
                .doesNotThrowAnyException();
    }

    @Test
    void connectionRefusedIsSwallowed() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            freePort = socket.getLocalPort();
        }
        assertThatCode(() -> client("http://127.0.0.1:" + freePort, Duration.ofMillis(500), Duration.ofMillis(500))
                .send(sample())).doesNotThrowAnyException();
    }

    @Test
    void readTimeoutIsEnforcedAndSwallowed() throws Exception {
        String url = start(201, 3000, new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>(),
                new CompletableFuture<>());
        HttpNotificationClient client = client(url, Duration.ofSeconds(1), Duration.ofMillis(300));

        long start = System.nanoTime();
        assertThatCode(() -> client.send(sample())).doesNotThrowAnyException();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("debe cortar por el timeout de lectura, no esperar al servidor lento")
                .isLessThan(2500);
    }

    private static NotificationRequest sample() {
        return new NotificationRequest("LOW_STOCK", "t", "m", null, null, "PRODUCT", UUID.randomUUID());
    }
}
