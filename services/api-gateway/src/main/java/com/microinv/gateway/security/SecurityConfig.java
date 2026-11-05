package com.microinv.gateway.security;

import com.microinv.gateway.error.ApiErrorWriter;
import com.microinv.gateway.error.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

/**
 * Seguridad del gateway (contrato seccion 3): todo exige un JWT valido (HS256, iss, exp) salvo
 * POST /api/auth/register, POST /api/auth/login, GET /actuator/health y /ws/**. Los 401 salen en el
 * formato comun de error. Sin sesion, sin cookies, sin CORS (el frontend usa mismo origen via nginx).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Unica definicion de "ruta publica": la usan la autorizacion y el conversor de tokens. */
    static ServerWebExchangeMatcher publicEndpoints() {
        return ServerWebExchangeMatchers.matchers(
                ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login"),
                ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/actuator/health"),
                ServerWebExchangeMatchers.pathMatchers("/ws/**"));
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder,
            ApiErrorWriter errors) {
        ServerWebExchangeMatcher publicEndpoints = publicEndpoints();

        ServerAuthenticationEntryPoint unauthorized = (exchange, ex) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return errors.write(exchange, ErrorCode.UNAUTHORIZED);
        };
        // Con un solo rol no deberia ocurrir; por si acaso, mismo formato (el contrato no define un 403 generico).
        ServerAccessDeniedHandler denied = (exchange, ex) ->
                errors.write(exchange, HttpStatus.FORBIDDEN, ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());

        // En las rutas publicas se ignora cualquier Authorization (un token caducado no debe impedir
        // hacer login o registrarse); en el resto, Bearer estandar.
        ServerBearerTokenAuthenticationConverter bearer = new ServerBearerTokenAuthenticationConverter();
        ServerAuthenticationConverter converter = exchange -> publicEndpoints.matches(exchange)
                .flatMap(match -> match.isMatch() ? Mono.empty() : bearer.convert(exchange));

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // API sin cookies: solo Authorization: Bearer
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(auth -> auth
                        .matchers(publicEndpoints).permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenConverter(converter)
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(denied)
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder)))
                .build();
    }
}
