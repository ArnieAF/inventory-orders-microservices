package com.microinv.notifications.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del servicio. Se valida al arrancar y el arranque FALLA con un mensaje claro si falta
 * algo imprescindible. Los mensajes nunca incluyen el valor recibido (para no volcar secretos a los logs).
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties implements InitializingBean {

    /** Longitud mínima del secreto HS256 (§3 del contrato). */
    public static final int MIN_JWT_SECRET_LENGTH = 32;

    private final Jwt jwt = new Jwt();
    private final WebSocket websocket = new WebSocket();
    private String internalApiKey = "";

    public Jwt getJwt() {
        return jwt;
    }

    public WebSocket getWebsocket() {
        return websocket;
    }

    public String getInternalApiKey() {
        return internalApiKey;
    }

    public void setInternalApiKey(String internalApiKey) {
        this.internalApiKey = internalApiKey;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /** Valida la configuración; lanza {@link IllegalStateException} con un mensaje claro (sin valores). */
    public void validate() {
        String secret = jwt.getSecret();
        if (secret == null || secret.isBlank() || secret.length() < MIN_JWT_SECRET_LENGTH) {
            throw new IllegalStateException("JWT_SECRET is missing or too short: it must be set and have at least "
                    + MIN_JWT_SECRET_LENGTH + " characters");
        }
        if (jwt.getIssuer() == null || jwt.getIssuer().isBlank()) {
            throw new IllegalStateException("JWT_ISSUER must not be blank");
        }
        if (internalApiKey == null || internalApiKey.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_KEY is missing: it must be set to a non-empty value");
        }
        websocket.validate();
    }

    public static class Jwt {
        private String secret = "";
        private String issuer = "micro-inventory";

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }
    }

    public static class WebSocket {
        private List<String> allowedOrigins = new ArrayList<>();
        private Duration connectTimeout = Duration.ofSeconds(10);

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        /** Orígenes ya normalizados (sin duplicados), listos para comparar con la cabecera Origin. */
        public List<String> normalizedAllowedOrigins() {
            Set<String> result = new LinkedHashSet<>();
            if (allowedOrigins != null) {
                for (String origin : allowedOrigins) {
                    if (origin == null || origin.isBlank()) {
                        continue;
                    }
                    result.add(normalizeOrigin(origin));
                }
            }
            return List.copyOf(result);
        }

        void validate() {
            if (normalizedAllowedOrigins().isEmpty()) {
                throw new IllegalStateException(
                        "ALLOWED_ORIGINS must list at least one origin (comma separated, e.g. http://localhost:3000)");
            }
            if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
                throw new IllegalStateException("app.websocket.connect-timeout must be a positive duration");
            }
        }
    }

    /**
     * Normaliza un origen (esquema://host[:puerto]) a minúsculas y sin puerto por defecto ni barra final.
     * Rechaza comodines, esquemas distintos de http/https, rutas, credenciales, consultas y fragmentos.
     * No incluye el valor recibido en el mensaje de error.
     */
    public static String normalizeOrigin(String raw) {
        String value = raw.trim();
        if (value.contains("*")) {
            throw new IllegalStateException("ALLOWED_ORIGINS must not contain wildcards: list explicit origins");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("ALLOWED_ORIGINS contains an entry that is not a valid origin");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean validScheme = "http".equals(scheme) || "https".equals(scheme);
        boolean hasExtras = uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()));
        if (!validScheme || uri.getHost() == null || hasExtras) {
            throw new IllegalStateException(
                    "ALLOWED_ORIGINS entries must look like http(s)://host[:port] (no path, query or credentials)");
        }
        int port = uri.getPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (port == -1 || defaultPort ? "" : ":" + port);
    }
}
