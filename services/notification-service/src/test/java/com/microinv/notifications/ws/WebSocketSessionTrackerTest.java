package com.microinv.notifications.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class WebSocketSessionTrackerTest {

    private ThreadPoolTaskScheduler scheduler;
    private WebSocketSessionTracker tracker;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        tracker = new WebSocketSessionTracker(scheduler, Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    @Test
    void closesASessionThatNeverSendsAValidConnect() throws Exception {
        WebSocketSession session = session("s1");
        tracker.opened(session);
        verify(session, timeout(3000)).close(any(CloseStatus.class));
    }

    @Test
    void doesNotCloseASessionThatAuthenticatesInTime() throws Exception {
        WebSocketSession session = session("s2");
        tracker.opened(session);
        assertThat(tracker.authenticate("s2", new StompPrincipal("user"))).isTrue();
        Thread.sleep(700);
        verify(session, never()).close(any(CloseStatus.class));
        assertThat(tracker.principal("s2").getName()).isEqualTo("user");
    }

    @Test
    void aSecondAuthenticationOnTheSameSessionIsRefused() {
        tracker.opened(session("s3"));
        assertThat(tracker.authenticate("s3", new StompPrincipal("first"))).isTrue();
        assertThat(tracker.authenticate("s3", new StompPrincipal("second"))).isFalse();
        assertThat(tracker.principal("s3").getName()).isEqualTo("first");
    }

    @Test
    void unknownSessionsCannotAuthenticate() {
        assertThat(tracker.authenticate("ghost", new StompPrincipal("user"))).isFalse();
        assertThat(tracker.authenticate(null, new StompPrincipal("user"))).isFalse();
        assertThat(tracker.principal("ghost")).isNull();
        assertThat(tracker.principal(null)).isNull();
    }

    @Test
    void closedSessionsAreForgottenAndTheirTimerCancelled() throws Exception {
        WebSocketSession session = session("s4");
        tracker.opened(session);
        assertThat(tracker.openSessions()).isEqualTo(1);
        tracker.closed("s4");
        assertThat(tracker.openSessions()).isZero();
        Thread.sleep(700);
        verify(session, never()).close(any(CloseStatus.class));
    }
}
