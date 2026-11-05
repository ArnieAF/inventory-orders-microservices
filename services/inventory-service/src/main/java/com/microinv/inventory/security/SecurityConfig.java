package com.microinv.inventory.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.inventory.config.SecurityProperties;
import com.microinv.inventory.error.ApiErrorWriter;
import com.microinv.inventory.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Dos cadenas de seguridad independientes (contrato 3):
 * <ul>
 *   <li>{@code /internal/**}: solo {@code X-Internal-Key}. NO tiene resource server, asi que un JWT
 *       (valido o no) se ignora por completo.</li>
 *   <li>Resto: {@code /api/**} exige JWT HS256 valido (firma, {@code iss}, {@code exp}, {@code sub});
 *       solo {@code /actuator/health} (y {@code /error}) son publicos.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    ApiErrorWriter apiErrorWriter(ObjectMapper objectMapper) {
        return new ApiErrorWriter(objectMapper);
    }

    @Bean
    @Order(1)
    SecurityFilterChain internalFilterChain(HttpSecurity http, SecurityProperties properties,
                                            ApiErrorWriter errorWriter) throws Exception {
        http.securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint(errorWriter, false)))
                .addFilterBefore(new InternalApiKeyFilter(properties.internalApiKey(), errorWriter),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, ApiErrorWriter errorWriter)
            throws Exception {
        AuthenticationEntryPoint entryPoint = entryPoint(errorWriter, true);
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(entryPoint)
                        .jwt(j -> j.decoder(jwtDecoder)));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        SecretKey key = new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwtIssuer()),
                requiredClaims()));
        return decoder;
    }

    /** Un token sin {@code exp} no caducaria nunca y sin {@code sub} no identifica a nadie: se rechazan. */
    static OAuth2TokenValidator<Jwt> requiredClaims() {
        return jwt -> {
            if (jwt.getExpiresAt() == null) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "exp is required", null));
            }
            String sub = jwt.getSubject();
            if (sub == null || sub.isBlank()) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "sub is required", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static AuthenticationEntryPoint entryPoint(ApiErrorWriter errorWriter, boolean bearer) {
        return (request, response, authException) -> {
            if (bearer) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            }
            errorWriter.write(request, response, ErrorCode.UNAUTHORIZED.status().value(),
                    ErrorCode.UNAUTHORIZED.name(), "Authentication required");
        };
    }
}
