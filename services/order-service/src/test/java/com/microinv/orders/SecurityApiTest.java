package com.microinv.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microinv.orders.domain.OrderStatus;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SecurityApiTest extends AbstractApiTest {

    private static final String SUB = USER_ID.toString();

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer(token));
    }

    private void assertUnauthorizedInCommonFormat(ResultActions result, String path) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString("Jwt"))))
                .andExpect(content().string(not(containsString("signature"))));
    }

    // ---- sin token --------------------------------------------------------------------------------------------

    @Test
    void everyApiRouteWithoutTokenIs401() throws Exception {
        UUID id = seedOrder(OrderStatus.PENDING);
        assertUnauthorizedInCommonFormat(mvc.perform(get("/api/orders")), "/api/orders");
        assertUnauthorizedInCommonFormat(mvc.perform(get("/api/orders/summary")), "/api/orders/summary");
        assertUnauthorizedInCommonFormat(mvc.perform(get("/api/orders/" + id)), "/api/orders/" + id);
        assertUnauthorizedInCommonFormat(mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"X\",\"items\":[]}")), "/api/orders");
        assertUnauthorizedInCommonFormat(mvc.perform(patch("/api/orders/" + id + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}")),
                "/api/orders/" + id + "/status");

        assertThat(statusInDb(id)).isEqualTo("PENDING");
        verifyNoInteractions(inventory);
        verifyNoInteractions(notifications);
    }

    @Test
    void unauthenticatedRequestToUnknownPathIs401NotA404() throws Exception {
        assertUnauthorizedInCommonFormat(mvc.perform(get("/internal/stock/reserve")), "/internal/stock/reserve");
    }

    // ---- tokens invalidos -------------------------------------------------------------------------------------

    static Stream<Arguments> badTokens() {
        Instant future = Instant.now().plusSeconds(3600);
        return Stream.of(
                Arguments.of("garbage", "not.a.jwt"),
                Arguments.of("empty-looking", "a"),
                Arguments.of("expired", TestTokens.sign(
                        TestTokens.claims(SUB, "alice", TestTokens.ISSUER, Instant.now().minusSeconds(3600)).build(),
                        JWSAlgorithm.HS256, TestTokens.SECRET)),
                Arguments.of("wrong issuer", TestTokens.sign(
                        TestTokens.claims(SUB, "alice", "someone-else", future).build(),
                        JWSAlgorithm.HS256, TestTokens.SECRET)),
                Arguments.of("missing issuer", TestTokens.sign(
                        new JWTClaimsSet.Builder().subject(SUB).claim("username", "alice")
                                .expirationTime(java.util.Date.from(future)).build(),
                        JWSAlgorithm.HS256, TestTokens.SECRET)),
                Arguments.of("signed with another secret", TestTokens.sign(
                        TestTokens.claims(SUB, "alice", TestTokens.ISSUER, future).build(),
                        JWSAlgorithm.HS256, "another-secret-another-secret-another-secret!")),
                Arguments.of("HS512 instead of HS256", TestTokens.sign(
                        TestTokens.claims(SUB, "alice", TestTokens.ISSUER, future).build(),
                        JWSAlgorithm.HS512, TestTokens.SECRET + TestTokens.SECRET)),
                Arguments.of("alg none (unsigned)", TestTokens.unsigned(
                        TestTokens.claims(SUB, "alice", TestTokens.ISSUER, future).build())),
                Arguments.of("no exp", TestTokens.sign(
                        TestTokens.claims(SUB, "alice", TestTokens.ISSUER, null).build(),
                        JWSAlgorithm.HS256, TestTokens.SECRET)),
                Arguments.of("sub is not a uuid", TestTokens.sign(
                        TestTokens.claims("alice", "alice", TestTokens.ISSUER, future).build(),
                        JWSAlgorithm.HS256, TestTokens.SECRET)),
                Arguments.of("sub missing", TestTokens.sign(
                        new JWTClaimsSet.Builder().issuer(TestTokens.ISSUER).claim("username", "alice")
                                .expirationTime(java.util.Date.from(future)).build(),
                        JWSAlgorithm.HS256, TestTokens.SECRET)),
                Arguments.of("username missing", TestTokens.sign(
                        new JWTClaimsSet.Builder().issuer(TestTokens.ISSUER).subject(SUB)
                                .expirationTime(java.util.Date.from(future)).build(),
                        JWSAlgorithm.HS256, TestTokens.SECRET)),
                Arguments.of("username blank", TestTokens.sign(
                        TestTokens.claims(SUB, "  ", TestTokens.ISSUER, future).build(),
                        JWSAlgorithm.HS256, TestTokens.SECRET)),
                Arguments.of("username too long", TestTokens.sign(
                        TestTokens.claims(SUB, "u".repeat(65), TestTokens.ISSUER, future).build(),
                        JWSAlgorithm.HS256, TestTokens.SECRET)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badTokens")
    void invalidTokensAre401(String name, String token) throws Exception {
        assertUnauthorizedInCommonFormat(mvc.perform(withToken(get("/api/orders"), token)), "/api/orders");
        verifyNoInteractions(inventory);
    }

    @Test
    void wrongSchemeOrTokenOutsideTheAuthorizationHeaderIs401() throws Exception {
        String token = TestTokens.valid(USER_ID, USERNAME);
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Basic " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").param("access_token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").header("X-User-Id", USER_ID.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").header("X-Internal-Key", "test-only-internal-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        mvc.perform(withToken(get("/api/orders"), TestTokens.valid(USER_ID, USERNAME)))
                .andExpect(status().isOk());
    }

    // ---- /error (despacho de error del contenedor) ------------------------------------------------------------

    @Test
    void containerErrorDispatchIsAnsweredInTheCommonFormatEvenWithoutAToken() throws Exception {
        mvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
                    request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 404);
                    request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/api/orders/x");
                    return request;
                }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/x"));
    }

    @Test
    void errorPathCannotBeRequestedDirectlyWithoutAToken() throws Exception {
        assertUnauthorizedInCommonFormat(mvc.perform(get("/error")), "/error");
    }

    // ---- actuator ---------------------------------------------------------------------------------------------

    @Test
    void healthIsPublicAndRevealsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/info",
                "/actuator/metrics", "/actuator/heapdump"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(authed(get(path))).andExpect(status().isNotFound());
        }
    }
}
