package com.microinv.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microinv.auth.user.UserAccount;
import com.microinv.auth.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

/** REGISTRATION_ENABLED=false: el registro devuelve 403 REGISTRATION_DISABLED; el login sigue funcionando. */
@SpringBootTest(properties = "app.registration.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationDisabledTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void clean() {
        users.deleteAll();
    }

    @Test
    void registro_deshabilitado_403_sinCrearUsuario() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"email\":\"new@example.com\",\"password\":\"Sup3r-Secret-pw\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("REGISTRATION_DISABLED"))
                .andExpect(jsonPath("$.path").value("/api/auth/register"));
        assertThat(users.count()).isZero();
    }

    @Test
    void registro_deshabilitado_responde403AunqueElCuerpoSeaInvalido() throws Exception {
        // No se revela nada de las reglas de validacion cuando el registro esta cerrado.
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGISTRATION_DISABLED"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{roto"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGISTRATION_DISABLED"));
    }

    @Test
    void login_siguePermitido() throws Exception {
        users.saveAndFlush(new UserAccount(UUID.randomUUID(), "existing", "existing@example.com",
                encoder.encode("Sup3r-Secret-pw"), Instant.now()));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"existing\",\"password\":\"Sup3r-Secret-pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("existing"));
    }
}
