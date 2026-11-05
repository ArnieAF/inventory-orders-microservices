package com.microinv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Los valores rechazados por la validacion (email, password) jamas aparecen en los logs. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneTest {

    @Autowired MockMvc mvc;

    private void register(String body) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void valoresRechazadosPorLaValidacion_noSeRegistran(CapturedOutput output) throws Exception {
        // Email no ASCII y contrasena demasiado corta: 400, y ni el email ni la contrasena en el log.
        register("{\"username\":\"log_user\",\"email\":\"leak.me@exämple.com\",\"password\":\"pw-leak\"}");
        // Email correcto pero contrasena de 120 bytes.
        register("{\"username\":\"log_user\",\"email\":\"also.leak@example.com\",\"password\":\""
                + "€".repeat(40) + "\"}");
        // JSON roto con datos dentro.
        register("{\"username\":\"log_user\",\"email\":\"broken.leak@example.com\",\"password\":\"x-secret-pw\"");

        String logs = output.getAll();
        assertThat(logs).doesNotContain("leak.me").doesNotContain("pw-leak").doesNotContain("also.leak")
                .doesNotContain("broken.leak").doesNotContain("x-secret-pw").doesNotContain("€€");
    }

    @Test
    void elTokenYLaContrasenaNoSeRegistranEnElLogin(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"login_leak_user\",\"password\":\"login-secret-pw\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(output.getAll()).doesNotContain("login-secret-pw");
    }
}
