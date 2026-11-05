package com.microinv.inventory.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microinv.inventory.support.AbstractIntegrationTest;
import com.microinv.inventory.support.JwtTestSupport;
import com.microinv.inventory.support.TestSecrets;
import com.nimbusds.jose.JWSAlgorithm;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class SecurityTest extends AbstractIntegrationTest {

    private static final String BASE_SECRET_OTHER = "another-secret-that-is-long-enough-0123456789";

    private ResultActions getProductsWith(String authorization) throws Exception {
        var request = get("/api/products");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mvc.perform(request);
    }

    private void assertUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").isString())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(content().string(not(containsString("Exception"))));
    }

    // ---------- /api/** (JWT) ----------

    @Test
    void apiWithoutTokenIs401InCommonErrorFormat() throws Exception {
        assertUnauthorized(getProductsWith(null));
    }

    @Test
    void apiWithValidTokenIsAllowed() throws Exception {
        getProductsWith("Bearer " + JwtTestSupport.validToken()).andExpect(status().isOk());
    }

    @Test
    void apiRejectsMalformedAndForgedTokens() throws Exception {
        assertUnauthorized(getProductsWith("Bearer not-a-jwt"));
        assertUnauthorized(getProductsWith("Bearer "));
        assertUnauthorized(getProductsWith("Basic dXNlcjpwYXNz"));
        // firmado con otro secreto
        assertUnauthorized(getProductsWith("Bearer " + JwtTestSupport.token(BASE_SECRET_OTHER, JWSAlgorithm.HS256,
                TestSecrets.JWT_ISSUER, UUID.randomUUID().toString(), Instant.now().plusSeconds(600))));
        // sin firma (alg none)
        assertUnauthorized(getProductsWith("Bearer " + JwtTestSupport.unsignedToken()));
        // otro algoritmo HMAC con la clave correcta: solo se acepta HS256
        assertUnauthorized(getProductsWith("Bearer " + JwtTestSupport.token(TestSecrets.JWT_SECRET,
                JWSAlgorithm.HS384, TestSecrets.JWT_ISSUER, UUID.randomUUID().toString(),
                Instant.now().plusSeconds(600))));
        // exp en el pasado (mas alla de la tolerancia de reloj)
        assertUnauthorized(getProductsWith("Bearer " + JwtTestSupport.token(TestSecrets.JWT_SECRET,
                JWSAlgorithm.HS256, TestSecrets.JWT_ISSUER, UUID.randomUUID().toString(),
                Instant.now().minusSeconds(3600))));
        // emisor equivocado
        assertUnauthorized(getProductsWith("Bearer " + JwtTestSupport.token(TestSecrets.JWT_SECRET,
                JWSAlgorithm.HS256, "otro-emisor", UUID.randomUUID().toString(), Instant.now().plusSeconds(600))));
        // sin emisor
        assertUnauthorized(getProductsWith("Bearer " + JwtTestSupport.token(TestSecrets.JWT_SECRET,
                JWSAlgorithm.HS256, null, UUID.randomUUID().toString(), Instant.now().plusSeconds(600))));
        // sin exp (no caduca nunca) y sin sub
        assertUnauthorized(getProductsWith("Bearer " + JwtTestSupport.token(TestSecrets.JWT_SECRET,
                JWSAlgorithm.HS256, TestSecrets.JWT_ISSUER, UUID.randomUUID().toString(), null)));
        assertUnauthorized(getProductsWith("Bearer " + JwtTestSupport.token(TestSecrets.JWT_SECRET,
                JWSAlgorithm.HS256, TestSecrets.JWT_ISSUER, null, Instant.now().plusSeconds(600))));
    }

    @Test
    void apiDoesNotAcceptTheInternalKeyInsteadOfAJwt() throws Exception {
        assertUnauthorized(mvc.perform(get("/api/products").header("X-Internal-Key", TestSecrets.INTERNAL_KEY)));
    }

    @Test
    void everyApiRouteRequiresAuthentication() throws Exception {
        String id = UUID.randomUUID().toString();
        assertUnauthorized(mvc.perform(get("/api/products/summary")));
        assertUnauthorized(mvc.perform(get("/api/products/" + id)));
        assertUnauthorized(mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{}")));
        assertUnauthorized(mvc.perform(post("/api/products/" + id + "/stock-adjustments")
                .contentType(MediaType.APPLICATION_JSON).content("{}")));
        assertUnauthorized(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/products/" + id)));
    }

    @Test
    void unknownPathsRequireAuthenticationToo() throws Exception {
        assertUnauthorized(mvc.perform(get("/nothing-here")));
        assertUnauthorized(mvc.perform(get("/actuator/env")));
        assertUnauthorized(mvc.perform(get("/actuator/info")));
    }

    // ---------- /actuator/health (publico) ----------

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
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/heapdump",
                "/actuator/mappings", "/actuator/info"}) {
            mvc.perform(get(path).header("Authorization", bearer())).andExpect(status().isNotFound());
        }
    }

    // ---------- /internal/** (X-Internal-Key; sin JWT) ----------

    private static final String RELEASE_BODY = "{\"orderRef\":\"" + "00000000-0000-0000-0000-000000000001" + "\"}";

    @Test
    void internalWithoutKeyIs401() throws Exception {
        assertUnauthorized(mvc.perform(post("/internal/stock/release")
                .contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY)));
        assertUnauthorized(mvc.perform(post("/internal/stock/reserve")
                .contentType(MediaType.APPLICATION_JSON).content("{}")));
    }

    @Test
    void internalWithWrongOrEmptyKeyIs401() throws Exception {
        for (String key : new String[] {"wrong-key", "", " ", TestSecrets.INTERNAL_KEY + "x",
                TestSecrets.INTERNAL_KEY.substring(1), TestSecrets.INTERNAL_KEY.toUpperCase()}) {
            assertUnauthorized(mvc.perform(post("/internal/stock/release").header("X-Internal-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY)));
        }
    }

    @Test
    void internalRejectsAValidJwtWhenThereIsNoKey() throws Exception {
        assertUnauthorized(mvc.perform(post("/internal/stock/release").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY)));
        assertUnauthorized(mvc.perform(post("/internal/stock/reserve").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{}")));
    }

    @Test
    void internalRejectsAValidJwtEvenWithAWrongKey() throws Exception {
        assertUnauthorized(mvc.perform(post("/internal/stock/release").header("Authorization", bearer())
                .header("X-Internal-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY)));
    }

    @Test
    void internalWithTheRightKeyWorksWithoutAnyJwt() throws Exception {
        mvc.perform(post("/internal/stock/release").header("X-Internal-Key", TestSecrets.INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    void internalWithTheRightKeyAndABadBodyIs400NotAnAuthError() throws Exception {
        mvc.perform(post("/internal/stock/reserve").header("X-Internal-Key", TestSecrets.INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void internalGetOfUnknownRouteWithoutKeyIs401AndWithKeyIs404() throws Exception {
        assertUnauthorized(mvc.perform(get("/internal/stock/whatever")));
        mvc.perform(get("/internal/stock/whatever").header("X-Internal-Key", TestSecrets.INTERNAL_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void securityResponsesCarryNoBearerChallengeLeakOnInternalAndNoStackTraces() throws Exception {
        mvc.perform(post("/internal/stock/release").contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }
}
