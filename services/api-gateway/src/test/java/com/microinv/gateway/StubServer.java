package com.microinv.gateway;

import io.netty.handler.codec.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Backend simulado (Reactor Netty, sin dependencias extra ni red externa). Registra cada peticion
 * recibida y responde JSON {"backend": nombre, "method": .., "uri": ..}. Cabeceras de control:
 * {@code x-stub-status} fija el status y {@code x-stub-body} el cuerpo de respuesta.
 */
final class StubServer implements AutoCloseable {

    record Received(String method, String uri, Map<String, List<String>> headers, String body) {
        boolean hasHeader(String name) {
            return headers.containsKey(name.toLowerCase(Locale.ROOT));
        }

        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    final String name;
    final List<Received> received = new CopyOnWriteArrayList<>();
    private final DisposableServer server;
    private volatile Duration delay = Duration.ZERO;

    private StubServer(String name, DisposableServer server) {
        this.name = name;
        this.server = server;
    }

    /** Stub HTTP generico. */
    static StubServer http(String name) {
        StubServer[] self = new StubServer[1];
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .handle((req, res) -> req.receive().aggregate().asString().defaultIfEmpty("").flatMap(body -> {
                    Map<String, List<String>> headers = copy(req.requestHeaders());
                    self[0].received.add(new Received(req.method().name(), req.uri(), headers, body));
                    String statusHeader = req.requestHeaders().get("x-stub-status");
                    int status = statusHeader == null ? 200 : Integer.parseInt(statusHeader);
                    String override = req.requestHeaders().get("x-stub-body");
                    String json = override != null ? override
                            : "{\"backend\":\"" + self[0].name + "\",\"method\":\"" + req.method().name()
                                    + "\",\"uri\":\"" + req.uri().replace("\"", "'") + "\"}";
                    return Mono.delay(self[0].delay)
                            .then(res.status(status).header("Content-Type", "application/json")
                                    .sendString(Mono.just(json)).then());
                }))
                .bindNow();
        self[0] = new StubServer(name, server);
        return self[0];
    }

    /** Stub WebSocket: en /ws hace eco ("echo:" + mensaje) y registra las cabeceras del handshake. */
    static StubServer websocket(String name) {
        StubServer[] self = new StubServer[1];
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .route(routes -> routes.ws("/ws", (in, out) -> {
                    self[0].received.add(new Received("WS", "/ws", copy(in.headers()), ""));
                    return out.sendString(in.receive().asString().map(msg -> "echo:" + msg)).then();
                }))
                .bindNow();
        self[0] = new StubServer(name, server);
        return self[0];
    }

    String httpUri() {
        return "http://127.0.0.1:" + server.port();
    }

    String wsUri() {
        return "ws://127.0.0.1:" + server.port();
    }

    void setDelay(Duration delay) {
        this.delay = delay;
    }

    void reset() {
        received.clear();
    }

    @Override
    public void close() {
        server.disposeNow();
    }

    private static Map<String, List<String>> copy(HttpHeaders headers) {
        Map<String, List<String>> out = new TreeMap<>();
        headers.names().forEach(n -> out.put(n.toLowerCase(Locale.ROOT), headers.getAll(n)));
        return out;
    }
}
