package com.microinv.notifications.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica las llamadas servicio→servicio (§3): header {@code X-Internal-Key} comparado en tiempo constante con
 * {@code INTERNAL_API_KEY}. Solo se registra en la cadena de {@code /internal/**}, que no incluye soporte de JWT.
 * Nunca registra el valor recibido ni el esperado.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Key";
    public static final String AUTHORITY = "ROLE_INTERNAL_SERVICE";

    private final byte[] expectedDigest;

    public InternalApiKeyFilter(String expectedKey) {
        this.expectedDigest = sha256(expectedKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && matches(provided)) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new PreAuthenticatedAuthenticationToken("internal-service", null,
                    List.of(new SimpleGrantedAuthority(AUTHORITY))));
            SecurityContextHolder.setContext(context);
        } else if (logger.isDebugEnabled()) {
            logger.debug("Internal request rejected: missing or invalid " + HEADER);
        }
        chain.doFilter(request, response);
    }

    /**
     * Comparación en tiempo constante: se comparan los resúmenes SHA-256 (longitud fija, así tampoco se filtra la
     * longitud de la clave) con {@link MessageDigest#isEqual}.
     */
    boolean matches(String provided) {
        return MessageDigest.isEqual(expectedDigest, sha256(provided));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
