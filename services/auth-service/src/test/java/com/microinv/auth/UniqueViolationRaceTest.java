package com.microinv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.auth.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Traduccion de la violacion REAL de UNIQUE (H2 en modo PostgreSQL + Flyway) a 409. Dos registros
 * concurrentes superan ambos el pre-chequeo (una barrera en el hash de la contrasena, que ocurre despues
 * del pre-chequeo y antes del INSERT, los sincroniza); el segundo INSERT lo rechaza la BD.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class UniqueViolationRaceTest {

    private static final String RACE_PASSWORD = "Race-Pw-12345";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;
    @MockitoSpyBean PasswordEncoder passwordEncoder;

    @BeforeEach
    void clean() {
        users.deleteAll();
    }

    private record Result(int status, JsonNode body) {
    }

    private Callable<Result> registration(String username, String email) {
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"" + RACE_PASSWORD + "\"}";
        return () -> {
            var response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse();
            return new Result(response.getStatus(), json.readTree(response.getContentAsString()));
        };
    }

    /** Lanza ambos registros a la vez, con barrera tras el pre-chequeo para forzar el INSERT concurrente. */
    private List<Result> race(Callable<Result> a, Callable<Result> b) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            barrier.await(15, TimeUnit.SECONDS); // ambos ya pasaron existsByUsername/existsByEmail
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(eq(RACE_PASSWORD));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> fa = pool.submit(a);
            Future<Result> fb = pool.submit(b);
            return new ArrayList<>(List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS)));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertOneCreatedOneConflict(List<Result> results, String conflictCode) {
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(201, 409);
        Result conflict = results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
        assertThat(conflict.body().get("code").asText()).isEqualTo(conflictCode);
        assertThat(conflict.body().get("status").asInt()).isEqualTo(409);
        assertThat(conflict.body().has("details")).isFalse();
    }

    @Test
    void mismoUsername_distintoEmail_unoCreado_otroUSERNAME_TAKEN(CapturedOutput output) throws Exception {
        List<Result> results = race(
                registration("race_user", "race.one.victim@example.com"),
                registration("RACE_USER", "race.two.victim@example.com"));

        assertOneCreatedOneConflict(results, "USERNAME_TAKEN");
        assertThat(users.count()).isEqualTo(1);
        assertNoUserDataInLogs(output);
    }

    @Test
    void distintoUsername_mismoEmail_unoCreado_otroEMAIL_TAKEN(CapturedOutput output) throws Exception {
        List<Result> results = race(
                registration("race_one", "race.same.victim@example.com"),
                registration("race_two", "RACE.SAME.VICTIM@example.com"));

        assertOneCreatedOneConflict(results, "EMAIL_TAKEN");
        assertThat(users.count()).isEqualTo(1);
        assertNoUserDataInLogs(output);
    }

    @Test
    void mismoUsernameYEmail_unoCreado_otroConflicto_nuncaUn500(CapturedOutput output) throws Exception {
        List<Result> results = race(
                registration("race_both", "race.both.victim@example.com"),
                registration("race_both", "race.both.victim@example.com"));

        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(201, 409);
        assertThat(results).extracting(Result::status).doesNotContain(500);
        assertThat(users.count()).isEqualTo(1);
        assertNoUserDataInLogs(output);
    }

    /** El log del driver/Hibernate para un UNIQUE incluye el valor en conflicto (email): no debe salir. */
    private static void assertNoUserDataInLogs(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain("victim@example.com").doesNotContain(RACE_PASSWORD);
    }
}
