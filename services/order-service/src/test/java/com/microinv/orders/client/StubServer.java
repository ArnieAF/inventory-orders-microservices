package com.microinv.orders.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Servidor HTTP real (JDK) en un puerto aleatorio: permite probar timeouts y codigos de estado de verdad. */
final class StubServer implements AutoCloseable {

    record Request(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    record Response(int status, String body, long delayMs) {
        static Response of(int status, String body) {
            return new Response(status, body, 0);
        }

        static Response slow(long delayMs) {
            return new Response(200, "{}", delayMs);
        }
    }

    private final HttpServer server;
    final List<Request> requests = new CopyOnWriteArrayList<>();

    private StubServer(Function<Request, Response> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, handler));
        server.start();
    }

    static StubServer start(Function<Request, Response> handler) throws IOException {
        return new StubServer(handler);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Request last() {
        return requests.get(requests.size() - 1);
    }

    /** URL de un puerto en el que no escucha nadie (conexion rechazada). */
    static String closedPortUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private void handle(HttpExchange exchange, Function<Request, Response> handler) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                Map.copyOf(exchange.getRequestHeaders()), body);
        requests.add(request);
        Response response = handler.apply(request);
        if (response.delayMs() > 0) {
            try {
                Thread.sleep(response.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            if (response.status() == 204) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            }
        } catch (IOException ignored) {
            // el cliente ya cerro (timeout): normal en estas pruebas
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
