package com.microinv.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

/** /ws/** es publico (sin JWT en el handshake) y se proxifica por WebSocket hacia notification-service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketProxyTest {

    static final StubServer NOTIFICATION_WS = StubServer.websocket("notification-ws");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ROUTE_NOTIFICATION_WS_URI", NOTIFICATION_WS::wsUri);
        registry.add("JWT_SECRET", () -> TestJwt.SECRET);
    }

    @AfterAll
    static void stop() {
        NOTIFICATION_WS.close();
    }

    @LocalServerPort int port;

    @BeforeEach
    void reset() {
        NOTIFICATION_WS.reset();
    }

    private List<String> echo(HttpHeaders handshakeHeaders, String message) {
        List<String> received = new CopyOnWriteArrayList<>();
        new ReactorNettyWebSocketClient().execute(URI.create("ws://127.0.0.1:" + port + "/ws"), handshakeHeaders,
                        session -> session.send(Mono.just(session.textMessage(message)))
                                .thenMany(session.receive().take(1).map(WebSocketMessage::getPayloadAsText)
                                        .doOnNext(received::add))
                                .then(session.close()))
                .block(Duration.ofSeconds(15));
        return received;
    }

    @Test
    void handshakeSinAuthorization_funciona_yElMensajeLlegaAlBackend() {
        List<String> received = echo(new HttpHeaders(), "hola");
        assertThat(received).containsExactly("echo:hola");
        assertThat(NOTIFICATION_WS.received).hasSize(1);
    }

    @Test
    void handshake_ignoraUnAuthorizationInvalido_porqueWsEsPublico() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer token-basura");
        assertThat(echo(headers, "x")).containsExactly("echo:x");
    }

    @Test
    void handshake_eliminaCabecerasInternas_yConservaOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Key", "clave-falsa");
        headers.add("X-User-Id", "otro");
        headers.add("X-Username", "admin");
        headers.add(HttpHeaders.ORIGIN, "http://localhost:3000");

        assertThat(echo(headers, "ping")).containsExactly("echo:ping");

        StubServer.Received handshake = NOTIFICATION_WS.received.get(0);
        assertThat(handshake.hasHeader("x-internal-key")).isFalse();
        assertThat(handshake.hasHeader("x-user-id")).isFalse();
        assertThat(handshake.hasHeader("x-username")).isFalse();
        assertThat(handshake.header("origin")).as("notification-service valida Origin").isEqualTo("http://localhost:3000");
    }
}
