package com.microinv.inventory.security;

import com.microinv.inventory.error.ApiErrorWriter;
import com.microinv.inventory.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica las llamadas servicio a servicio de {@code /internal/**} con {@code X-Internal-Key}.
 * La comparacion es en tiempo constante: se comparan los SHA-256 de ambas claves con
 * {@link MessageDigest#isEqual(byte[], byte[])} (los resumenes tienen siempre la misma longitud, asi
 * tampoco se filtra la longitud de la clave). Ausente o incorrecta: 401 generico, sin pistas.
 * <p>
 * No es un bean de Spring a proposito: Boot registraria cualquier {@code Filter} bean para TODAS las
 * rutas; aqui solo debe colgar de la cadena de {@code /internal/**}.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Key";

    private final byte[] expectedDigest;
    private final ApiErrorWriter errorWriter;

    public InternalApiKeyFilter(String expectedKey, ApiErrorWriter errorWriter) {
        this.expectedDigest = sha256(expectedKey);
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(sha256(provided), expectedDigest)) {
            SecurityContextHolder.clearContext();
            errorWriter.write(request, response, ErrorCode.UNAUTHORIZED.status().value(),
                    ErrorCode.UNAUTHORIZED.name(), "Authentication required");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
