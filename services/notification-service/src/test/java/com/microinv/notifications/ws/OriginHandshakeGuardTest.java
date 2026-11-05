package com.microinv.notifications.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OriginHandshakeGuardTest {

    private final OriginHandshakeGuard guard =
            new OriginHandshakeGuard(List.of("http://localhost:3000", "https://app.example.com"));

    private boolean accepts(String origin, MockHttpServletResponse servletResponse) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        if (origin != null) {
            servletRequest.addHeader("Origin", origin);
        }
        return guard.beforeHandshake(new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse), null, new HashMap<>());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "https://app.example.com"})
    void allowedOriginsPass(String origin) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(accepts(origin, response)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example", "http://localhost:3001", "https://localhost:3000",
            "http://localhost:3000/", "http://LOCALHOST:3000", "http://localhost:3000.evil.example", "null", "*",
            "", "http://", "://", "http://[::1", "http://localhost:3000, http://evil.example",
            "http://localhost:3000%0d%0aX-Injected: 1"})
    void anythingElseIsForbiddenWithoutParsingIt(String origin) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(accepts(origin, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void requestsWithoutOriginAreNotBrowsers() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(accepts(null, response)).isTrue();
    }

    @Test
    void sanitizeCutsLongValuesAndRemovesControlCharacters() {
        assertThat(OriginHandshakeGuard.sanitize("a\r\nb\tc")).isEqualTo("a??b?c");
        assertThat(OriginHandshakeGuard.sanitize("x".repeat(500))).hasSize(100);
    }
}
