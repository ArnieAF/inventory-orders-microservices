package com.microinv.notifications.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microinv.notifications.security.JwtDecoderFactory;
import com.microinv.notifications.support.TestTokens;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketSession;

/** Reglas de autenticación y de destinos del canal de entrada, sin red (mensajes sintéticos). */
class StompAuthChannelInterceptorTest {

    private static final String SESSION = "session-1";

    private final MessageChannel channel = mock(MessageChannel.class);
    private final UUID userId = UUID.randomUUID();
    private ThreadPoolTaskScheduler scheduler;
    private WebSocketSessionTracker tracker;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        tracker = new WebSocketSessionTracker(scheduler, Duration.ofMinutes(5));
        interceptor = new StompAuthChannelInterceptor(
                JwtDecoderFactory.create(TestTokens.SECRET, TestTokens.ISSUER), tracker);
        open(SESSION);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    private void open(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        tracker.opened(session);
    }

    private Message<byte[]> frame(StompCommand command, String sessionId, String destination,
                                  String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (command == StompCommand.SUBSCRIBE) {
            accessor.setSubscriptionId("sub-0");
        }
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> connect(String authorization) {
        return frame(StompCommand.CONNECT, SESSION, null, authorization);
    }

    private void connectAsUser() {
        interceptor.preSend(connect("Bearer " + TestTokens.valid(userId)), channel);
    }

    private static void assertRejected(Runnable action, String publicMessage) {
        assertThatThrownBy(action::run).isInstanceOf(StompSecurityException.class).hasMessage(publicMessage);
    }

    // ---- CONNECT

    @Test
    void connectWithAValidTokenSetsThePrincipalToTheSubject() {
        Message<byte[]> message = connect("Bearer " + TestTokens.valid(userId));
        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo(userId.toString());
        assertThat(tracker.principal(SESSION).getName()).isEqualTo(userId.toString());
    }

    @Test
    void connectNormalizesAnUppercaseSubject() {
        Message<byte[]> message = connect("Bearer " + TestTokens.withSubject(userId.toString().toUpperCase()));
        interceptor.preSend(message, channel);
        assertThat(tracker.principal(SESSION).getName()).isEqualTo(userId.toString());
    }

    @Test
    void stompCommandBehavesLikeConnect() {
        Message<byte[]> message = frame(StompCommand.STOMP, SESSION, null, "Bearer " + TestTokens.valid(userId));
        interceptor.preSend(message, channel);
        assertThat(tracker.principal(SESSION)).isNotNull();
    }

    @Test
    void bearerSchemeIsCaseInsensitive() {
        interceptor.preSend(connect("bearer " + TestTokens.valid(userId)), channel);
        assertThat(tracker.principal(SESSION)).isNotNull();
    }

    @Test
    void connectWithoutTokenIsRejected() {
        assertRejected(() -> interceptor.preSend(connect(null), channel),
                StompSecurityException.AUTHENTICATION_REQUIRED);
        assertRejected(() -> interceptor.preSend(connect("   "), channel),
                StompSecurityException.AUTHENTICATION_REQUIRED);
        assertThat(tracker.principal(SESSION)).isNull();
    }

    @Test
    void connectWithBadTokensIsRejectedWithAGenericMessage() {
        String[] headers = {
                "Basic dXNlcjpwYXNz",
                "Bearer ",
                "Bearer not-a-jwt",
                "Bearer " + TestTokens.expired(userId),
                "Bearer " + TestTokens.signedWithOtherSecret(userId),
                "Bearer " + TestTokens.tampered(userId, UUID.randomUUID()),
                "Bearer " + TestTokens.unsigned(userId),
                "Bearer " + TestTokens.hs512(userId),
                "Bearer " + TestTokens.wrongIssuer(userId),
                "Bearer " + TestTokens.withSubject("not-a-uuid"),
                "Bearer " + TestTokens.withoutExpiration(userId),
                TestTokens.valid(userId)};
        for (String header : headers) {
            assertThatThrownBy(() -> interceptor.preSend(connect(header), channel))
                    .as("header %s", header)
                    .isInstanceOf(StompSecurityException.class)
                    .hasMessage(StompSecurityException.INVALID_CREDENTIALS);
        }
        assertThat(tracker.principal(SESSION)).isNull();
    }

    @Test
    void theRejectionNeverEchoesTheToken() {
        String token = TestTokens.expired(userId);
        assertThatThrownBy(() -> interceptor.preSend(connect("Bearer " + token), channel))
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(token));
    }

    @Test
    void aSecondConnectOnTheSameSessionIsRejected() {
        connectAsUser();
        assertRejected(() -> interceptor.preSend(
                connect("Bearer " + TestTokens.valid(UUID.randomUUID())), channel),
                StompSecurityException.ALREADY_CONNECTED);
        assertThat(tracker.principal(SESSION).getName()).isEqualTo(userId.toString());
    }

    // ---- SUBSCRIBE / UNSUBSCRIBE

    @Test
    void subscribeWithoutConnectIsRejected() {
        assertRejected(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, SESSION, StompDestinations.BROADCAST_TOPIC, null), channel),
                StompSecurityException.AUTHENTICATION_REQUIRED);
    }

    @Test
    void authenticatedSessionsMaySubscribeToTheTwoAllowedDestinationsOnly() {
        connectAsUser();
        for (String destination : new String[] {StompDestinations.BROADCAST_TOPIC,
                StompDestinations.USER_QUEUE_SUBSCRIPTION}) {
            Message<byte[]> message = frame(StompCommand.SUBSCRIBE, SESSION, destination, null);
            assertThat(interceptor.preSend(message, channel)).isSameAs(message);
            // El usuario de la suscripción sale del registro de la sesión.
            assertThat(MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class).getUser().getName())
                    .isEqualTo(userId.toString());
        }
        for (String destination : new String[] {"/topic/other", "/queue/notifications", "/topic/*",
                "/user/queue/notifications/", "/user/" + UUID.randomUUID() + "/queue/notifications",
                "/queue/notifications-user" + SESSION, "/app/x"}) {
            assertThatThrownBy(() -> interceptor.preSend(
                    frame(StompCommand.SUBSCRIBE, SESSION, destination, null), channel))
                    .as("destination %s", destination)
                    .isInstanceOf(StompSecurityException.class)
                    .hasMessage(StompSecurityException.DESTINATION_NOT_ALLOWED);
        }
    }

    @Test
    void subscribeWithoutDestinationIsRejected() {
        connectAsUser();
        assertRejected(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, SESSION, null, null), channel),
                StompSecurityException.DESTINATION_NOT_ALLOWED);
    }

    @Test
    void unsubscribeNeedsAnAuthenticatedSession() {
        assertRejected(() -> interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, SESSION, null, null), channel),
                StompSecurityException.AUTHENTICATION_REQUIRED);
        connectAsUser();
        assertThatCode(() -> interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, SESSION, null, null), channel))
                .doesNotThrowAnyException();
    }

    private Message<byte[]> subscription(StompCommand command, String subscriptionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(SESSION);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void aConnectionCannotAccumulateSubscriptions() {
        connectAsUser();
        int max = WebSocketSessionTracker.MAX_SUBSCRIPTIONS_PER_SESSION;
        for (int i = 0; i < max; i++) {
            interceptor.preSend(subscription(StompCommand.SUBSCRIBE, "s" + i, StompDestinations.BROADCAST_TOPIC),
                    channel);
        }
        // Repetir un identificador ya registrado no ocupa otro hueco.
        assertThatCode(() -> interceptor.preSend(
                subscription(StompCommand.SUBSCRIBE, "s0", StompDestinations.BROADCAST_TOPIC), channel))
                .doesNotThrowAnyException();
        assertRejected(() -> interceptor.preSend(
                subscription(StompCommand.SUBSCRIBE, "one-too-many", StompDestinations.BROADCAST_TOPIC), channel),
                StompSecurityException.TOO_MANY_SUBSCRIPTIONS);

        // Cancelar una suscripción libera su hueco.
        interceptor.preSend(subscription(StompCommand.UNSUBSCRIBE, "s3", null), channel);
        assertThatCode(() -> interceptor.preSend(
                subscription(StompCommand.SUBSCRIBE, "one-too-many", StompDestinations.USER_QUEUE_SUBSCRIPTION),
                channel)).doesNotThrowAnyException();
    }

    @Test
    void theSubscriptionLimitIsPerConnection() {
        connectAsUser();
        open("session-2");
        for (int i = 0; i < WebSocketSessionTracker.MAX_SUBSCRIPTIONS_PER_SESSION; i++) {
            interceptor.preSend(subscription(StompCommand.SUBSCRIBE, "s" + i, StompDestinations.BROADCAST_TOPIC),
                    channel);
        }
        assertThat(tracker.addSubscription("session-2", "s0")).isTrue();
    }

    @Test
    void subscribeWithoutAUsableIdIsRejected() {
        connectAsUser();
        assertRejected(() -> interceptor.preSend(
                subscription(StompCommand.SUBSCRIBE, null, StompDestinations.BROADCAST_TOPIC), channel),
                StompSecurityException.OPERATION_NOT_ALLOWED);
        assertRejected(() -> interceptor.preSend(
                subscription(StompCommand.SUBSCRIBE, "x".repeat(65), StompDestinations.BROADCAST_TOPIC), channel),
                StompSecurityException.OPERATION_NOT_ALLOWED);
    }

    // ---- SEND y el resto: denegado por defecto

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"SEND", "ACK", "NACK", "BEGIN", "COMMIT", "ABORT"})
    void clientsCannotSendOrUseTransactionsEvenWhenAuthenticated(StompCommand command) {
        connectAsUser();
        assertRejected(() -> interceptor.preSend(
                frame(command, SESSION, StompDestinations.BROADCAST_TOPIC, null), channel),
                StompSecurityException.OPERATION_NOT_ALLOWED);
    }

    @Test
    void sendIsRejectedBeforeConnectToo() {
        assertRejected(() -> interceptor.preSend(
                frame(StompCommand.SEND, SESSION, StompDestinations.BROADCAST_TOPIC, null), channel),
                StompSecurityException.OPERATION_NOT_ALLOWED);
    }

    @Test
    void disconnectAndHeartbeatsAreAlwaysAllowed() {
        assertThatCode(() -> interceptor.preSend(frame(StompCommand.DISCONNECT, SESSION, null, null), channel))
                .doesNotThrowAnyException();
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        heartbeat.setSessionId(SESSION);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders());
        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }
}
