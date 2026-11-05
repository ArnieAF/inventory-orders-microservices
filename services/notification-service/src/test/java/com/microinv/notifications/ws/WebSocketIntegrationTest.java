package com.microinv.notifications.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microinv.notifications.support.IntegrationTestBase;
import com.microinv.notifications.support.TestTokens;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Prueba de integración REAL del WebSocket (§7): servidor en puerto aleatorio, cliente STOMP nativo
 * ({@link WebSocketStompClient}) y, para lo que un cliente de librería no permite, un cliente WebSocket crudo.
 * El tiempo máximo para el CONNECT está configurado a 1,5 s en {@link IntegrationTestBase}.
 */
@ExtendWith(OutputCaptureExtension.class)
class WebSocketIntegrationTest extends IntegrationTestBase {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final long NEGATIVE_WAIT_MILLIS = 1200;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebSocketSessionTracker tracker;

    @Autowired
    private SimpUserRegistry userRegistry;

    @Autowired
    private SimpleBrokerMessageHandler brokerHandler;

    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    private ThreadPoolTaskScheduler clientScheduler;
    private final List<WebSocketStompClient> stompClients = new ArrayList<>();
    private final List<WebSocketSession> rawSessions = new ArrayList<>();

    @BeforeEach
    void setUpClients() {
        clientScheduler = new ThreadPoolTaskScheduler();
        clientScheduler.setPoolSize(2);
        clientScheduler.setThreadNamePrefix("test-ws-client-");
        clientScheduler.initialize();
    }

    @AfterEach
    void tearDownClients() {
        for (WebSocketStompClient client : stompClients) {
            client.stop();
        }
        for (WebSocketSession session : rawSessions) {
            try {
                session.close();
            } catch (IOException ignored) {
                // ya cerrada
            }
        }
        clientScheduler.shutdown();
    }

    // ------------------------------------------------------------------ utilidades

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    /** Manejador de sesión que anota lo que llega: CONNECTED, frames ERROR y errores de transporte. */
    private static final class Handler extends StompSessionHandlerAdapter {
        final CompletableFuture<StompHeaders> connected = new CompletableFuture<>();
        final BlockingQueue<StompHeaders> errorFrames = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> transportErrors = new LinkedBlockingQueue<>();

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            connected.complete(connectedHeaders);
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errorFrames.add(headers);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            transportErrors.add(exception);
        }
    }

    private record Attempt(CompletableFuture<StompSession> future, Handler handler) {
        StompSession session() throws Exception {
            return future.get(5, TimeUnit.SECONDS);
        }

        String errorMessage() throws InterruptedException {
            StompHeaders frame = handler.errorFrames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("ERROR frame").isNotNull();
            return frame.getFirst("message");
        }
    }

    private WebSocketStompClient newStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setTaskScheduler(clientScheduler);
        client.setDefaultHeartbeat(new long[] {10_000, 10_000});
        client.setReceiptTimeLimit(5_000);
        stompClients.add(client);
        return client;
    }

    private Attempt attempt(String authorizationHeader, String origin) {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (origin != null) {
            handshake.setOrigin(origin);
        }
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.setHeartbeat(new long[] {10_000, 10_000});
        if (authorizationHeader != null) {
            connectHeaders.add("Authorization", authorizationHeader);
        }
        Handler handler = new Handler();
        return new Attempt(newStompClient().connectAsync(wsUrl(), handshake, connectHeaders, handler), handler);
    }

    private Attempt attemptWithToken(String token) {
        return attempt(token == null ? null : "Bearer " + token, ALLOWED_ORIGIN);
    }

    private StompSession connect(UUID user) throws Exception {
        return attemptWithToken(TestTokens.valid(user)).session();
    }

    /**
     * Suscribe y espera a que el broker del servidor tenga registrada la suscripción. El broker simple no envía
     * RECEIPT, así que se consulta su registro real (si no, un mensaje publicado enseguida podría perderse).
     */
    private BlockingQueue<String> subscribe(UUID user, StompSession session, String destination) throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        int before = brokerSubscriptions(user, destination);
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (brokerSubscriptions(user, destination) <= before && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(brokerSubscriptions(user, destination)).as("broker subscriptions of %s to %s", user, destination)
                .isGreaterThan(before);
        return messages;
    }

    /** Suscripciones ya registradas en el broker para las sesiones de ese usuario y ese destino. */
    private int brokerSubscriptions(UUID user, String destination) {
        SimpUser simpUser = userRegistry.getUser(user.toString());
        if (simpUser == null) {
            return 0;
        }
        int total = 0;
        for (SimpSession simpSession : simpUser.getSessions()) {
            // La suscripción a /user/queue/... el broker la registra como /queue/...-user<sessionId>.
            String brokerDestination = StompDestinations.USER_QUEUE_SUBSCRIPTION.equals(destination)
                    ? StompDestinations.USER_QUEUE + "-user" + simpSession.getId() : destination;
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            accessor.setDestination(brokerDestination);
            Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
            List<String> found = brokerHandler.getSubscriptionRegistry().findSubscriptions(probe)
                    .get(simpSession.getId());
            if (found != null) {
                total += found.size();
            }
        }
        return total;
    }

    private JsonNode postNotification(String type, UUID target, String title) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", type);
        body.put("title", title);
        body.put("message", "Message of " + title);
        if (target == null) {
            body.putNull("targetUserId");
        } else {
            body.put("targetUserId", target.toString());
        }
        body.put("actorUserId", UUID.randomUUID().toString());
        body.put("entityType", "ORDER");
        body.put("entityId", UUID.randomUUID().toString());
        String response = mvc.perform(post("/internal/notifications")
                        .header("X-Internal-Key", TestTokens.INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode next(BlockingQueue<String> queue) throws Exception {
        String payload = queue.poll(5, TimeUnit.SECONDS);
        assertThat(payload).as("a notification should have been delivered").isNotNull();
        return objectMapper.readTree(payload);
    }

    private static void assertNothingArrives(BlockingQueue<String> queue) throws InterruptedException {
        assertThat(queue.poll(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS)).as("no message expected").isNull();
    }

    private static void assertConnectionEnds(StompSession session) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (session.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(session.isConnected()).as("the server should close the connection after the ERROR").isFalse();
    }

    // ------------------------------------------------------------------ cliente crudo

    private static final class RawClient extends TextWebSocketHandler {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        volatile WebSocketSession session;

        @Override
        public void afterConnectionEstablished(WebSocketSession established) {
            session = established;
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            closed.complete(status);
        }

        void send(String text) throws IOException {
            session.sendMessage(new TextMessage(text));
        }

        /** Siguiente frame STOMP no vacío (ignora latidos). */
        String nextFrame() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                String message = messages.poll(200, TimeUnit.MILLISECONDS);
                if (message != null && !message.isBlank()) {
                    return message;
                }
            }
            return null;
        }
    }

    private RawClient rawConnect(String origin) throws Exception {
        RawClient client = new RawClient();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (origin != null) {
            headers.setOrigin(origin);
        }
        headers.setSecWebSocketProtocol("v12.stomp");
        WebSocketSession session = new StandardWebSocketClient()
                .execute(client, headers, URI.create(wsUrl())).get(5, TimeUnit.SECONDS);
        rawSessions.add(session);
        return client;
    }

    private static String stompFrame(String command, String headers, String body) {
        return command + "\n" + headers + "\n" + body + "\u0000";
    }

    private static String connectFrame(String token) {
        return stompFrame("CONNECT", "accept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token + "\n", "");
    }

    /** Handshake HTTP a mano: devuelve el código de estado de la respuesta (101 = aceptado, 403 = rechazado). */
    private int handshakeStatus(String origin) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            StringBuilder request = new StringBuilder()
                    .append("GET /ws HTTP/1.1\r\n")
                    .append("Host: localhost:").append(port).append("\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n");
            if (origin != null) {
                request.append("Origin: ").append(origin).append("\r\n");
            }
            request.append("\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            assertThat(statusLine).startsWith("HTTP/1.1 ");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    // ------------------------------------------------------------------ entrega en tiempo real

    @Test
    void connectedFrameNegotiatesTheTenSecondHeartbeat() throws Exception {
        Attempt attempt = attemptWithToken(TestTokens.valid(userA));
        attempt.session();
        StompHeaders connected = attempt.handler.connected.get(5, TimeUnit.SECONDS);
        assertThat(connected.getHeartbeat()).containsExactly(10_000L, 10_000L);
    }

    @Test
    void broadcastReachesEverySubscribedUserWithTheContractJson() throws Exception {
        StompSession sessionA = connect(userA);
        StompSession sessionB = connect(userB);
        BlockingQueue<String> topicA = subscribe(userA, sessionA, "/topic/notifications");
        BlockingQueue<String> topicB = subscribe(userB, sessionB, "/topic/notifications");

        JsonNode created = postNotification("NEW_ORDER", null, "Broadcast one");

        for (BlockingQueue<String> topic : List.of(topicA, topicB)) {
            JsonNode received = next(topic);
            assertThat(received.get("id").asText()).isEqualTo(created.get("id").asText());
            assertThat(received.get("type").asText()).isEqualTo("NEW_ORDER");
            assertThat(received.get("title").asText()).isEqualTo("Broadcast one");
            assertThat(received.get("message").asText()).isEqualTo("Message of Broadcast one");
            assertThat(received.has("targetUserId")).isTrue();
            assertThat(received.get("targetUserId").isNull()).isTrue();
            assertThat(received.get("actorUserId").asText()).isEqualTo(created.get("actorUserId").asText());
            assertThat(received.get("entityType").asText()).isEqualTo("ORDER");
            assertThat(received.get("entityId").asText()).isEqualTo(created.get("entityId").asText());
            assertThat(received.get("createdAt").isTextual()).as("createdAt is an ISO-8601 string").isTrue();
            assertThat(Instant.parse(received.get("createdAt").asText())).isBefore(Instant.now().plusSeconds(5));
            assertThat(received.get("read").asBoolean()).isFalse();
        }
    }

    @Test
    void targetedNotificationsReachOnlyTheirRecipient() throws Exception {
        StompSession sessionA = connect(userA);
        StompSession sessionB = connect(userB);
        BlockingQueue<String> queueA = subscribe(userA, sessionA, "/user/queue/notifications");
        BlockingQueue<String> queueB = subscribe(userB, sessionB, "/user/queue/notifications");
        BlockingQueue<String> topicA = subscribe(userA, sessionA, "/topic/notifications");
        BlockingQueue<String> topicB = subscribe(userB, sessionB, "/topic/notifications");

        JsonNode created = postNotification("ORDER_STATUS_CHANGED", userA, "Only for A");

        JsonNode received = next(queueA);
        assertThat(received.get("id").asText()).isEqualTo(created.get("id").asText());
        assertThat(received.get("targetUserId").asText()).isEqualTo(userA.toString());
        assertThat(received.get("read").asBoolean()).isFalse();
        // B no recibe la dirigida a A, ni por su cola ni por el tema de difusión; A tampoco la repite.
        assertNothingArrives(queueB);
        assertNothingArrives(topicA);
        assertNothingArrives(topicB);
        assertNothingArrives(queueA);

        // Y al revés.
        JsonNode forB = postNotification("ORDER_STATUS_CHANGED", userB, "Only for B");
        assertThat(next(queueB).get("id").asText()).isEqualTo(forB.get("id").asText());
        assertNothingArrives(queueA);
    }

    @Test
    void aTargetedNotificationReachesEverySessionOfThatUser() throws Exception {
        StompSession firstTab = connect(userA);
        StompSession secondTab = connect(userA);
        BlockingQueue<String> first = subscribe(userA, firstTab, "/user/queue/notifications");
        BlockingQueue<String> second = subscribe(userA, secondTab, "/user/queue/notifications");

        JsonNode created = postNotification("LOW_STOCK", userA, "Two tabs");

        assertThat(next(first).get("id").asText()).isEqualTo(created.get("id").asText());
        assertThat(next(second).get("id").asText()).isEqualTo(created.get("id").asText());
    }

    @Test
    void theUserNameIsTheSubjectEvenWhenTheTokenUsesUppercase() throws Exception {
        StompSession session = attemptWithToken(TestTokens.withSubject(userA.toString().toUpperCase())).session();
        BlockingQueue<String> queue = subscribe(userA, session, "/user/queue/notifications");
        JsonNode created = postNotification("LOW_STOCK", userA, "Canonical subject");
        assertThat(next(queue).get("id").asText()).isEqualTo(created.get("id").asText());
    }

    @Test
    void nothingIsDeliveredToAUserWhoOnlySubscribedToHisOwnQueueWhenOthersGetBroadcasts() throws Exception {
        StompSession sessionA = connect(userA);
        BlockingQueue<String> queueA = subscribe(userA, sessionA, "/user/queue/notifications");
        postNotification("NEW_ORDER", null, "A broadcast");
        assertNothingArrives(queueA);
    }

    @Test
    void theConnectionStaysUsableAfterTheConnectTimeoutHasPassed() throws Exception {
        StompSession session = connect(userA);
        BlockingQueue<String> topic = subscribe(userA, session, "/topic/notifications");
        Thread.sleep(2500); // > connect-timeout (1,5 s): el temporizador debió cancelarse al autenticar
        assertThat(session.isConnected()).isTrue();
        JsonNode created = postNotification("NEW_ORDER", null, "Still alive");
        assertThat(next(topic).get("id").asText()).isEqualTo(created.get("id").asText());
    }

    // ------------------------------------------------------------------ autenticación en CONNECT

    @Test
    void connectWithoutATokenGetsAnErrorFrameAndTheConnectionCloses() throws Exception {
        Attempt attempt = attemptWithToken(null);
        assertThat(attempt.errorMessage()).isEqualTo("Authentication required");
        assertThatThrownBy(() -> attempt.future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void connectWithAnInvalidTokenGetsAGenericErrorFrame() throws Exception {
        UUID someone = UUID.randomUUID();
        String[] badTokens = {
                "garbage",
                TestTokens.expired(someone),
                TestTokens.signedWithOtherSecret(someone),
                TestTokens.tampered(someone, UUID.randomUUID()),
                TestTokens.unsigned(someone),
                TestTokens.hs512(someone),
                TestTokens.wrongIssuer(someone),
                TestTokens.withSubject("not-a-uuid"),
                TestTokens.withoutExpiration(someone)};
        for (String token : badTokens) {
            Attempt attempt = attemptWithToken(token);
            assertThat(attempt.errorMessage()).as("token %s", token.substring(0, Math.min(12, token.length())))
                    .isEqualTo("Invalid or expired credentials");
            assertThatThrownBy(() -> attempt.future.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        }
    }

    @Test
    void aTokenWithoutTheBearerSchemeIsRejected() throws Exception {
        Attempt attempt = attempt(TestTokens.valid(userA), ALLOWED_ORIGIN);
        assertThat(attempt.errorMessage()).isEqualTo("Invalid or expired credentials");
    }

    @Test
    void errorFramesDoNotExposeInternalDetails() throws Exception {
        Attempt attempt = attemptWithToken(TestTokens.expired(userA));
        StompHeaders frame = attempt.handler.errorFrames.poll(5, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        String everything = frame.toString();
        assertThat(everything).doesNotContain("Exception", "org.springframework", "com.microinv", "java.",
                "Jwt", "expired at", TestTokens.SECRET);
    }

    @Test
    void anAttackerCannotUseAnotherUsersIdentityByTamperingWithTheToken() throws Exception {
        Attempt attempt = attemptWithToken(TestTokens.tampered(userA, userB));
        assertThat(attempt.errorMessage()).isEqualTo("Invalid or expired credentials");
    }

    // ------------------------------------------------------------------ control de destinos

    @ParameterizedTest
    @ValueSource(strings = {"/topic/other", "/topic/*", "/topic/notifications/x", "/queue/notifications",
            "/user/queue/other", "/app/anything", "/user/11111111-1111-1111-1111-111111111111/queue/notifications"})
    void subscribingToADisallowedDestinationIsRejected(String destination) throws Exception {
        Attempt attempt = attemptWithToken(TestTokens.valid(userA));
        StompSession session = attempt.session();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // nunca debería llegar nada
            }
        });
        assertThat(attempt.errorMessage()).isEqualTo("Destination not allowed");
        assertConnectionEnds(session);
    }

    @Test
    void aUserCannotSubscribeToTheQueueOfAnotherUser() throws Exception {
        Attempt attackerAttempt = attemptWithToken(TestTokens.valid(userA));
        StompSession attacker = attackerAttempt.session();
        BlockingQueue<String> stolen = new LinkedBlockingQueue<>();
        attacker.subscribe("/user/" + userB + "/queue/notifications", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                stolen.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        assertThat(attackerAttempt.errorMessage()).isEqualTo("Destination not allowed");

        postNotification("ORDER_STATUS_CHANGED", userB, "Private to B");
        assertNothingArrives(stolen);
    }

    @Test
    void clientsCannotPublishIntoTheBroadcastTopic() throws Exception {
        StompSession listener = connect(userA);
        BlockingQueue<String> topic = subscribe(userA, listener, "/topic/notifications");
        Attempt senderAttempt = attemptWithToken(TestTokens.valid(userB));
        StompSession sender = senderAttempt.session();

        sender.send("/topic/notifications", "{\"forged\":true}".getBytes(StandardCharsets.UTF_8));

        assertThat(senderAttempt.errorMessage()).isEqualTo("Operation not allowed");
        assertNothingArrives(topic);
        assertConnectionEnds(sender);
    }

    @Test
    void clientsCannotSendToApplicationDestinations() throws Exception {
        Attempt attempt = attemptWithToken(TestTokens.valid(userA));
        StompSession session = attempt.session();
        session.send("/app/anything", "x".getBytes(StandardCharsets.UTF_8));
        assertThat(attempt.errorMessage()).isEqualTo("Operation not allowed");
    }

    // ------------------------------------------------------------------ Origin

    @Test
    void handshakeOriginIsValidatedAgainstAllowedOrigins() throws Exception {
        assertThat(handshakeStatus("http://localhost:3000")).isEqualTo(101);
        assertThat(handshakeStatus("http://localhost:5173")).isEqualTo(101);

        assertThat(handshakeStatus("http://evil.example")).isEqualTo(403);
        assertThat(handshakeStatus("http://localhost:3001")).isEqualTo(403);
        assertThat(handshakeStatus("https://localhost:3000")).isEqualTo(403);
        assertThat(handshakeStatus("http://localhost:3000.evil.example")).isEqualTo(403);
        assertThat(handshakeStatus("http://evil.example/http://localhost:3000")).isEqualTo(403);
        assertThat(handshakeStatus("null")).isEqualTo(403);
        assertThat(handshakeStatus("*")).isEqualTo(403);
    }

    @Test
    void aStompClientFromADisallowedOriginCannotConnect() {
        Attempt attempt = attempt("Bearer " + TestTokens.valid(userA), "http://evil.example");
        assertThatThrownBy(() -> attempt.future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void clientsWithoutAnOriginHeaderAreNotBrowsersAndStillNeedAValidToken() throws Exception {
        // Sin Origin (cliente no navegador): el handshake se admite, pero el CONNECT exige JWT igualmente.
        Attempt withoutToken = attempt(null, null);
        assertThat(withoutToken.errorMessage()).isEqualTo("Authentication required");
        Attempt withToken = attempt("Bearer " + TestTokens.valid(userA), null);
        assertThat(withToken.session().isConnected()).isTrue();
    }

    @Test
    void thereIsNoSockJsEndpoint() throws Exception {
        mvc.perform(get("/ws/info")).andExpect(status().isNotFound());
        mvc.perform(get("/ws")).andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
    }

    // ------------------------------------------------------------------ tiempo para el CONNECT y límites

    @Test
    void aConnectionThatNeverSendsConnectIsClosedAfterTheTimeout() throws Exception {
        long start = System.nanoTime();
        RawClient client = rawConnect(ALLOWED_ORIGIN);

        CloseStatus status = client.closed.get(6, TimeUnit.SECONDS);

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(status.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(elapsedMillis).as("closed by the CONNECT timeout, not before").isGreaterThanOrEqualTo(1200);
    }

    @Test
    void sendingOnlyHeartbeatsDoesNotKeepAnUnauthenticatedConnectionAlive() throws Exception {
        RawClient client = rawConnect(ALLOWED_ORIGIN);
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (!client.closed.isDone() && System.nanoTime() < deadline) {
            try {
                client.send("\n");
            } catch (IOException closed) {
                break;
            }
            Thread.sleep(150);
        }
        // Spring rechaza el latido previo al CONNECT (sesión desconocida) y, si no, lo cierra el temporizador:
        // lo importante es que los latidos NO mantienen viva una conexión que no se autentica.
        assertThat(client.closed.get(2, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void closedConnectionsAreForgottenByTheServer() throws Exception {
        int baseline = tracker.openSessions();
        RawClient client = rawConnect(ALLOWED_ORIGIN);
        assertThat(tracker.openSessions()).isEqualTo(baseline + 1);
        client.closed.get(6, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (tracker.openSessions() != baseline && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(tracker.openSessions()).isEqualTo(baseline);
    }

    @Test
    void rawStompConnectWithAValidTokenWorksAndANonStompFirstFrameIsRejected() throws Exception {
        RawClient good = rawConnect(ALLOWED_ORIGIN);
        good.send(connectFrame(TestTokens.valid(userA)));
        assertThat(good.nextFrame()).startsWith("CONNECTED");

        RawClient garbage = rawConnect(ALLOWED_ORIGIN);
        garbage.send("hello, this is not stomp");
        // Cualquier cosa que no sea un CONNECT válido termina cerrando la conexión (error o temporizador).
        assertThat(garbage.closed.get(6, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void sendOrSubscribeBeforeConnectIsRejectedAndNothingIsBroadcast() throws Exception {
        StompSession listener = connect(userA);
        BlockingQueue<String> topic = subscribe(userA, listener, "/topic/notifications");

        RawClient sender = rawConnect(ALLOWED_ORIGIN);
        sender.send(stompFrame("SEND", "destination:/topic/notifications\ncontent-type:application/json\n",
                "{\"forged\":true}"));
        String frame = sender.nextFrame();
        // Antes del CONNECT Spring ya descarta el frame ("Unknown session"); si llegara a nuestro interceptor sería
        // "Operation not allowed". En ambos casos: ERROR + cierre, sin difundir nada.
        assertThat(frame).startsWith("ERROR").containsPattern("message:(Invalid message|Operation not allowed)");
        sender.closed.get(5, TimeUnit.SECONDS);

        RawClient subscriber = rawConnect(ALLOWED_ORIGIN);
        subscriber.send(stompFrame("SUBSCRIBE", "id:sub-0\ndestination:/topic/notifications\n", ""));
        assertThat(subscriber.nextFrame()).startsWith("ERROR")
                .containsPattern("message:(Invalid message|Authentication required)");
        subscriber.closed.get(5, TimeUnit.SECONDS);

        assertNothingArrives(topic);
    }

    @Test
    void aSecondConnectCannotSwitchTheIdentityOfASession() throws Exception {
        RawClient client = rawConnect(ALLOWED_ORIGIN);
        client.send(connectFrame(TestTokens.valid(userA)));
        assertThat(client.nextFrame()).startsWith("CONNECTED");
        client.send(connectFrame(TestTokens.valid(userB)));
        // Spring ya prohíbe un segundo CONNECT en la sesión ("Invalid message"); nuestro interceptor es la 2.ª barrera.
        assertThat(client.nextFrame()).startsWith("ERROR").containsPattern("message:(Invalid message|Already connected)");
        client.closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void messagesLargerThanEightKilobytesCloseTheConnection() throws Exception {
        RawClient client = rawConnect(ALLOWED_ORIGIN);
        client.send(connectFrame(TestTokens.valid(userA)));
        assertThat(client.nextFrame()).startsWith("CONNECTED");

        client.send(stompFrame("SEND", "destination:/topic/notifications\n", "x".repeat(20_000)));

        assertThat(client.closed.get(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void oversizedFirstMessageClosesTheConnectionToo() throws Exception {
        RawClient client = rawConnect(ALLOWED_ORIGIN);
        client.send("x".repeat(20_000));
        assertThat(client.closed.get(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void serverLogsNeverContainTokens(CapturedOutput output) throws Exception {
        String expired = TestTokens.expired(userA);
        String valid = TestTokens.valid(userA);
        attemptWithToken(expired).errorMessage();
        attemptWithToken(valid).session();
        assertThat(output.getAll()).doesNotContain(expired).doesNotContain(valid)
                .doesNotContain(TestTokens.SECRET).doesNotContain(TestTokens.INTERNAL_KEY);
    }
}
