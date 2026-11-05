package com.microinv.notifications.security;

import com.microinv.notifications.api.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** 401 {@code UNAUTHORIZED} en el formato común (§5), sin detalles sobre por qué falló la autenticación. */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter writer;
    private final boolean advertiseBearer;

    public ApiAuthenticationEntryPoint(ApiErrorWriter writer, boolean advertiseBearer) {
        this.writer = writer;
        this.advertiseBearer = advertiseBearer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (advertiseBearer) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        writer.write(request, response, HttpServletResponse.SC_UNAUTHORIZED);
    }
}
