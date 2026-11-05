package com.microinv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microinv.auth.user.AuthService;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * Un fallo inesperado (p. ej. SQL) jamas llega al cliente ni a los logs con datos del usuario:
 * 500 INTERNAL_ERROR generico, y el log de ERROR solo lleva clases y ruta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class UnexpectedErrorTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuthService authService;

    private static final String LOGIN_BODY = "{\"username\":\"u\",\"password\":\"p\"}";

    @Test
    void errorInesperado_500_sinDetallesInternos() throws Exception {
        when(authService.login(any())).thenThrow(
                new IllegalStateException("could not execute statement [select * from users where password_hash = 'x']"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"))
                .andExpect(content().string(not(containsString("select"))))
                .andExpect(content().string(not(containsString("password_hash"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void errorInesperado_elLogDeErrorSoloLlevaClasesYRuta_nuncaMensajesNiTraza(CapturedOutput output) throws Exception {
        RuntimeException root = new IllegalArgumentException("root-cause-secret victim@example.com");
        when(authService.login(any())).thenThrow(new IllegalStateException(
                "could not execute statement; email=victim@example.com password=hunter2hunter2", root));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isInternalServerError());

        String logs = output.getAll();
        // Si: clase de la excepcion, clase de la causa raiz y ruta.
        assertThat(logs).contains("Unhandled error").contains("POST /api/auth/login")
                .contains("IllegalStateException").contains("IllegalArgumentException");
        // No: mensajes (pueden llevar valores rechazados, SQL o secretos) ni traza completa.
        assertThat(logs).doesNotContain("victim@example.com").doesNotContain("hunter2hunter2")
                .doesNotContain("root-cause-secret").doesNotContain("could not execute statement")
                .doesNotContain("\tat com.microinv.auth");
    }

    @Test
    void errorDeInfraestructura503_esSERVICE_UNAVAILABLE_noINTERNAL_ERROR() throws Exception {
        // AsyncRequestTimeoutException es una excepcion estandar de Spring MVC que resuelve a 503.
        when(authService.login(any())).thenThrow(new AsyncRequestTimeoutException());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"));
    }

    @Test
    void paginaDeError503_tambienEsSERVICE_UNAVAILABLE() throws Exception {
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 503)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/auth/me"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.path").value("/api/auth/me"));
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
