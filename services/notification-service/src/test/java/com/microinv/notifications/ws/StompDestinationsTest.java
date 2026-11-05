package com.microinv.notifications.ws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StompDestinationsTest {

    @Test
    void onlyTheTwoContractDestinationsAreSubscribable() {
        assertThat(StompDestinations.isSubscribable("/topic/notifications")).isTrue();
        assertThat(StompDestinations.isSubscribable("/user/queue/notifications")).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "/topic/other", "/topic/", "/topic/*", "/topic/**", "/topic/notifications/", "/topic/notifications/x",
            "/topic/Notifications", "topic/notifications", " /topic/notifications", "/topic/notifications ",
            "/queue/notifications", "/queue/notifications-user123",
            "/user/queue/notifications/", "/user/queue/other", "/user/queue/*",
            "/user/11111111-1111-1111-1111-111111111111/queue/notifications",
            "/user//queue/notifications", "/user/queue/notifications?x=1", "/app/anything", "/ws", "/"})
    void everythingElseIsRejected(String destination) {
        assertThat(StompDestinations.isSubscribable(destination)).isFalse();
    }
}
