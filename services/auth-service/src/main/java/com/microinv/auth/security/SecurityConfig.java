package com.microinv.auth.security;

import com.microinv.auth.common.ApiErrorWriter;
import com.microinv.auth.common.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Seguridad de auth-service: resource server JWT (HS256) sin sesion. Publicos: register, login y
 * health. Todo lo demas exige un JWT valido; los errores salen en el formato comun del contrato.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // coste por defecto (10)
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, ApiErrorWriter errors)
            throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher credentialEndpoints = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/api/auth/register"),
                paths.matcher(HttpMethod.POST, "/api/auth/login"));
        RequestMatcher health = paths.matcher(HttpMethod.GET, "/actuator/health");
        RequestMatcher errorPage = paths.matcher("/error");

        AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            errors.write(request, response, ErrorCode.UNAUTHORIZED);
        };
        // Con un solo rol no deberia ocurrir; por si acaso, mismo formato (el contrato no define un codigo 403 generico).
        AccessDeniedHandler denied = (request, response, ex) -> errors.write(request, response, 403, ErrorCode.UNAUTHORIZED);

        // Un token caducado en la cabecera NO debe impedir hacer login/registro (son publicos):
        // en esos dos endpoints se ignora cualquier Authorization.
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        BearerTokenResolver resolver = request ->
                credentialEndpoints.matches(request) ? null : defaultResolver.resolve(request);

        http
                .csrf(AbstractHttpConfigurer::disable) // API sin cookies ni sesion: solo Authorization: Bearer
                .cors(AbstractHttpConfigurer::disable) // sin CORS: el frontend usa mismo origen via nginx
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(credentialEndpoints, health, errorPage).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(resolver)
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(denied)
                        .jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }
}
