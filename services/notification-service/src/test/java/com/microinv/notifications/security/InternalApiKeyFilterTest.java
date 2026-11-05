package com.microinv.notifications.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class InternalApiKeyFilterTest {

    private final InternalApiKeyFilter filter = new InternalApiKeyFilter("the-expected-key");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchesOnlyTheExactKey() {
        assertThat(filter.matches("the-expected-key")).isTrue();
        assertThat(filter.matches("the-expected-kez")).isFalse();
        assertThat(filter.matches("the-expected-key ")).isFalse();
        assertThat(filter.matches("The-Expected-Key")).isFalse();
        assertThat(filter.matches("the-expected")).isFalse();
        assertThat(filter.matches("the-expected-key-and-more")).isFalse();
        assertThat(filter.matches("")).isFalse();
    }

    @Test
    void authenticatesOnlyWithTheRightHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/notifications");
        request.addHeader(InternalApiKeyFilter.HEADER, "the-expected-key");
        AuthSeen seen = run(request);
        assertThat(seen.authority).isEqualTo(InternalApiKeyFilter.AUTHORITY);
    }

    @Test
    void doesNotAuthenticateWithoutTheHeader() throws ServletException, IOException {
        AuthSeen seen = run(new MockHttpServletRequest("POST", "/internal/notifications"));
        assertThat(seen.authority).isNull();
    }

    @Test
    void doesNotAuthenticateWithAWrongKeyOrAJwt() throws ServletException, IOException {
        MockHttpServletRequest wrong = new MockHttpServletRequest("POST", "/internal/notifications");
        wrong.addHeader(InternalApiKeyFilter.HEADER, "nope");
        assertThat(run(wrong).authority).isNull();

        MockHttpServletRequest jwtOnly = new MockHttpServletRequest("POST", "/internal/notifications");
        jwtOnly.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x");
        assertThat(run(jwtOnly).authority).isNull();
    }

    private AuthSeen run(MockHttpServletRequest request) throws ServletException, IOException {
        AuthSeen seen = new AuthSeen();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && !authentication.getAuthorities().isEmpty()) {
                    seen.authority = authentication.getAuthorities().iterator().next().getAuthority();
                }
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen;
    }

    private static final class AuthSeen {
        private String authority;
    }
}
