package com.microinv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.auth.user.UserAccount;
import com.microinv.auth.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Contrato 6.1: register / login / me contra H2 (modo PostgreSQL) + Flyway. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

    private static final String PASSWORD = "Sup3r-Secret-pw";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JwtDecoder jwtDecoder;
    @MockitoSpyBean PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDatabase() {
        users.deleteAll();
    }

    // ---------- helpers ----------

    private ResultActions register(String username, String email, String password) throws Exception {
        return postJson("/api/auth/register", body(username, email, password));
    }

    private ResultActions login(String username, String password) throws Exception {
        return postJson("/api/auth/login", "{\"username\":" + q(username) + ",\"password\":" + q(password) + "}");
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(String username, String email, String password) {
        return "{\"username\":" + q(username) + ",\"email\":" + q(email) + ",\"password\":" + q(password) + "}";
    }

    private static String q(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private JsonNode registerOk(String username, String email) throws Exception {
        String res = register(username, email, PASSWORD).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private void assertErrorFormat(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.path").isString())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("at com."))))
                .andExpect(content().string(not(containsString("at org."))));
    }

    // ---------- register ----------

    @Test
    void register_ok_devuelveAuthResponseConUsuarioNormalizado() throws Exception {
        register("Alice_01", "Alice@Example.COM", PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.id").isString())
                .andExpect(jsonPath("$.user.username").value("alice_01"))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))));
        assertThat(users.findByUsername("alice_01")).isPresent();
    }

    @Test
    void register_guardaHashBcryptNuncaLaContrasena() throws Exception {
        registerOk("bob", "bob@example.com");
        UserAccount stored = users.findByUsername("bob").orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD).doesNotContain(PASSWORD);
        assertThat(stored.getEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void register_tokenEmitidoTieneLosClaimsDelContrato() throws Exception {
        JsonNode res = registerOk("carol", "carol@example.com");
        Jwt jwt = jwtDecoder.decode(res.get("accessToken").asText());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("micro-inventory");
        assertThat(jwt.getSubject()).isEqualTo(res.at("/user/id").asText());
        assertThat(UUID.fromString(jwt.getSubject())).isNotNull();
        assertThat(jwt.<String>getClaim("username")).isEqualTo("carol");
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(3600));
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
    }

    @Test
    void register_usernameDuplicadoSinImportarMayusculas_409() throws Exception {
        registerOk("dave", "dave@example.com");
        assertErrorFormat(register("DAVE", "other@example.com", PASSWORD), 409, "USERNAME_TAKEN");
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void register_emailDuplicadoSinImportarMayusculas_409() throws Exception {
        registerOk("erin", "erin@example.com");
        assertErrorFormat(register("erin2", "ERIN@Example.com", PASSWORD), 409, "EMAIL_TAKEN");
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void register_validacion_rechazaEntradasInvalidas() throws Exception {
        String longEmail = "a".repeat(60) + "@" + "b".repeat(56) + ".com"; // 121 > 120 (local <= 64: solo falla por longitud)
        String[][] cases = {
                // username, email, password, campo esperado en details
                {"ab", "ok@example.com", PASSWORD, "username"},
                {"a".repeat(33), "ok@example.com", PASSWORD, "username"},
                {"bad name", "ok@example.com", PASSWORD, "username"},
                {"bad<script>", "ok@example.com", PASSWORD, "username"},
                {"acentuadoñ", "ok@example.com", PASSWORD, "username"},
                {"ok_user", "not-an-email", PASSWORD, "email"},
                {"ok_user", "", PASSWORD, "email"},
                {"ok_user", longEmail, PASSWORD, "email"},
                {"ok_user", "ok@example.com", "1234567", "password"},
                {"ok_user", "ok@example.com", "a".repeat(73), "password"},
                // 40 caracteres de 3 bytes = 120 bytes UTF-8 (> 72): BCrypt los truncaria o fallaria
                {"ok_user", "ok@example.com", "€".repeat(40), "password"},
                {null, "ok@example.com", PASSWORD, "username"},
                {"ok_user", null, PASSWORD, "email"},
                {"ok_user", "ok@example.com", null, "password"},
        };
        for (String[] c : cases) {
            register(c[0], c[1], c[2])
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[*].field").value(hasItem(c[3])))
                    .andExpect(content().string(not(containsString(PASSWORD))));
        }
        assertThat(users.count()).isZero();
    }

    @Test
    void register_limitesExactosSonValidos() throws Exception {
        register("a".repeat(32), "a".repeat(60) + "@" + "b".repeat(55) + ".com", "p".repeat(72)).andExpect(status().isCreated()); // email = 120 exactos
        register("abc", "min@example.com", "12345678").andExpect(status().isCreated());
        register("Z.z-9_", "dots@example.com", PASSWORD).andExpect(status().isCreated());
    }

    @Test
    void register_cuerpoMalformado_o_sinCuerpo_400() throws Exception {
        assertErrorFormat(postJson("/api/auth/register", "{not json"), 400, "VALIDATION_ERROR");
        assertErrorFormat(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)), 400,
                "VALIDATION_ERROR");
    }

    @Test
    void register_documentoJsonEnorme_400_sinCargarloEntero() throws Exception {
        String huge = "{\"username\":\"" + "a".repeat(50_000) + "\",\"email\":\"x@example.com\",\"password\":\"" + PASSWORD + "\"}";
        assertErrorFormat(postJson("/api/auth/register", huge), 400, "VALIDATION_ERROR");
        String deep = "{\"a\":".repeat(40) + "1" + "}".repeat(40);
        assertErrorFormat(postJson("/api/auth/login", deep), 400, "VALIDATION_ERROR");
    }

    @Test
    void register_contentTypeNoSoportado_415_enFormatoComun() throws Exception {
        assertErrorFormat(mvc.perform(post("/api/auth/register").contentType(MediaType.TEXT_PLAIN).content("x")), 415,
                "VALIDATION_ERROR");
    }

    @Test
    void register_ignoraUnBearerCaducadoEnLaCabecera() throws Exception {
        String stale = TestJwt.expired(UUID.randomUUID().toString(), "ghost");
        mvc.perform(post("/api/auth/register").header(HttpHeaders.AUTHORIZATION, "Bearer " + stale)
                        .contentType(MediaType.APPLICATION_JSON).content(body("frank", "frank@example.com", PASSWORD)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer basura")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"frank\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    // ---------- login ----------

    @Test
    void login_ok_ignoraMayusculasEnElUsername() throws Exception {
        JsonNode registered = registerOk("grace", "grace@example.com");
        login("GRACE", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.id").value(registered.at("/user/id").asText()))
                .andExpect(jsonPath("$.user.username").value("grace"))
                .andExpect(jsonPath("$.user.email").value("grace@example.com"));
    }

    @Test
    void login_passwordIncorrecto_yUsuarioInexistente_dan_exactamenteLaMismaRespuesta() throws Exception {
        registerOk("heidi", "heidi@example.com");

        String wrongPassword = login("heidi", "otra-clave-distinta").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login("nadie", "otra-clave-distinta").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        JsonNode a = json.readTree(wrongPassword);
        JsonNode b = json.readTree(unknownUser);
        assertThat(a.get("code").asText()).isEqualTo("INVALID_CREDENTIALS").isEqualTo(b.get("code").asText());
        assertThat(a.get("status")).isEqualTo(b.get("status"));
        assertThat(a.get("message")).isEqualTo(b.get("message"));
        assertThat(a.has("details")).isFalse();
        assertThat(b.has("details")).isFalse();
    }

    @Test
    void login_usuarioInexistente_igualQueHaceBcryptContraUnHashFalso() throws Exception {
        login("fantasma", "alguna-clave-12").andExpect(status().isUnauthorized());
        // Se ejecuta una comparacion BCrypt (coste similar) aunque el usuario no exista.
        verify(passwordEncoder).matches(eq("alguna-clave-12"), startsWith("$2"));
    }

    @Test
    void login_conUsuarioExistente_tambienEjecutaUnaComparacionBcrypt() throws Exception {
        registerOk("ivan", "ivan@example.com");
        login("ivan", "equivocada-123").andExpect(status().isUnauthorized());
        verify(passwordEncoder).matches(eq("equivocada-123"), anyString());
    }

    @Test
    void login_validacionDeTamano_400() throws Exception {
        postJson("/api/auth/login", "{\"password\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postJson("/api/auth/login", "{\"username\":\"x\"}").andExpect(status().isBadRequest());
        postJson("/api/auth/login", "{\"username\":\"\",\"password\":\"x\"}").andExpect(status().isBadRequest());
        postJson("/api/auth/login", "{\"username\":\"x\",\"password\":\"\"}").andExpect(status().isBadRequest());
        login("a".repeat(33), "x").andExpect(status().isBadRequest());
        login("x", "a".repeat(73)).andExpect(status().isBadRequest());
        login("x", "€".repeat(40)).andExpect(status().isBadRequest());
    }

    // ---------- me ----------

    @Test
    void me_conTokenValido_devuelveElUsuario() throws Exception {
        JsonNode registered = registerOk("judy", "judy@example.com");
        String token = registered.get("accessToken").asText();
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(registered.at("/user/id").asText()))
                .andExpect(jsonPath("$.username").value("judy"))
                .andExpect(jsonPath("$.email").value("judy@example.com"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void me_sinToken_401_formatoComun() throws Exception {
        assertErrorFormat(mvc.perform(get("/api/auth/me")), 401, "UNAUTHORIZED");
    }

    @Test
    void me_tokenCaducado_401() throws Exception {
        JsonNode registered = registerOk("karl", "karl@example.com");
        String expired = TestJwt.expired(registered.at("/user/id").asText(), "karl");
        assertErrorFormat(mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)),
                401, "UNAUTHORIZED");
    }

    @Test
    void me_tokenManipulado_401() throws Exception {
        JsonNode registered = registerOk("laura", "laura@example.com");
        String token = registered.get("accessToken").asText();
        String tampered = TestJwt.tamperPayload(token, "\"username\":\"laura\"", "\"username\":\"admin\"");
        assertErrorFormat(mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)),
                401, "UNAUTHORIZED");
        // Firma truncada / basura
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token.substring(0, token.length() - 4)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer no.es.un.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_tokenFirmadoConOtroSecreto_ConOtroEmisor_oSinFirma_401() throws Exception {
        JsonNode registered = registerOk("mallory", "mallory@example.com");
        String id = registered.at("/user/id").asText();
        Instant now = Instant.now();
        String otherSecret = TestJwt.mint("another-secret-another-secret-another-secret", TestJwt.ISSUER, id, "mallory",
                now, now.plusSeconds(600));
        String otherIssuer = TestJwt.mint(TestJwt.SECRET, "evil-issuer", id, "mallory", now, now.plusSeconds(600));
        String noIssuer = TestJwt.mint(TestJwt.SECRET, null, id, "mallory", now, now.plusSeconds(600));
        String noSubject = TestJwt.mint(TestJwt.SECRET, TestJwt.ISSUER, null, "mallory", now, now.plusSeconds(600));
        for (String bad : new String[] {otherSecret, otherIssuer, noIssuer, noSubject, TestJwt.unsigned(id, "mallory")}) {
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void me_tokenValidoDeUsuarioInexistente_401() throws Exception {
        String token = TestJwt.valid(UUID.randomUUID().toString(), "ghost");
        assertErrorFormat(mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
                401, "UNAUTHORIZED");
    }

    @Test
    void me_subNoEsUuid_401() throws Exception {
        String token = TestJwt.valid("no-es-un-uuid", "x");
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_ignoraCabecerasDeIdentidadDelCliente() throws Exception {
        JsonNode registered = registerOk("nina", "nina@example.com");
        String token = registered.get("accessToken").asText();
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Id", UUID.randomUUID().toString()).header("X-Username", "admin"))
                .andExpect(jsonPath("$.username").value("nina"));
        mvc.perform(get("/api/auth/me").header("X-User-Id", registered.at("/user/id").asText())
                        .header("X-Username", "nina"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- resto de la superficie ----------

    @Test
    void health_publico_sinDetalles() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void actuator_soloExponeHealth() throws Exception {
        String token = TestJwt.valid(UUID.randomUUID().toString(), "x");
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/info",
                "/actuator/metrics", "/actuator/mappings"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isNotFound());
        }
    }

    @Test
    void rutaInexistente_sinToken_401_yConToken_404_enFormatoComun() throws Exception {
        assertErrorFormat(mvc.perform(get("/api/auth/nope")), 401, "UNAUTHORIZED");
        String token = TestJwt.valid(UUID.randomUUID().toString(), "x");
        assertErrorFormat(mvc.perform(get("/api/auth/nope").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
                404, "NOT_FOUND");
    }

    @Test
    void metodoNoPermitido_405_enFormatoComun() throws Exception {
        String token = TestJwt.valid(UUID.randomUUID().toString(), "x");
        assertErrorFormat(mvc.perform(get("/api/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
                405, "VALIDATION_ERROR");
    }

    @Test
    void errorDirecto_enFormatoComun() throws Exception {
        assertErrorFormat(mvc.perform(get("/error")), 404, "NOT_FOUND");
    }

    @Test
    void respuestasLlevanCabecerasDeSeguridadYNoRevelanElServidor() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
