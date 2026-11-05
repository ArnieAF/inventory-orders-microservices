package com.microinv.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microinv.auth.user.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Un fallo inesperado (p. ej. SQL) jamas llega al cliente: 500 INTERNAL_ERROR generico. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnexpectedErrorTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuthService authService;

    @Test
    void errorInesperado_500_sinDetallesInternos() throws Exception {
        when(authService.login(any())).thenThrow(
                new IllegalStateException("could not execute statement [select * from users where password_hash = 'x']"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"))
                .andExpect(content().string(not(containsString("select"))))
                .andExpect(content().string(not(containsString("password_hash"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
}
