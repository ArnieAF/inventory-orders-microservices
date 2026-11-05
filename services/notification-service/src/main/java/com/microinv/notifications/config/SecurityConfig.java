package com.microinv.notifications.config;

import com.microinv.notifications.api.ApiErrorWriter;
import com.microinv.notifications.security.ApiAuthenticationEntryPoint;
import com.microinv.notifications.security.InternalApiKeyFilter;
import com.microinv.notifications.security.JwtDecoderFactory;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Dos cadenas de seguridad independientes, sin sesión ni CSRF (API sin cookies):
 * <ul>
 *   <li>{@code /internal/**}: solo {@code X-Internal-Key}. No incluye soporte de Bearer: un JWT no sirve aquí.</li>
 *   <li>Resto: resource server JWT HS256. Públicos: {@code /actuator/health}, el handshake {@code /ws}
 *       (la autenticación va en el frame STOMP CONNECT) y {@code /error}.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public JwtDecoder jwtDecoder(AppProperties properties) {
        return JwtDecoderFactory.create(properties.getJwt().getSecret(), properties.getJwt().getIssuer());
    }

    @Bean
    @Order(1)
    public SecurityFilterChain internalFilterChain(HttpSecurity http, AppProperties properties,
                                                   ApiErrorWriter errorWriter) throws Exception {
        // El filtro NO es un @Bean (se registraría también como filtro de servlet y correría dos veces).
        InternalApiKeyFilter keyFilter = new InternalApiKeyFilter(properties.getInternalApiKey());
        http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(keyFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(InternalApiKeyFilter.AUTHORITY))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ApiAuthenticationEntryPoint(errorWriter, false)));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                              ApiErrorWriter errorWriter) throws Exception {
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(errorWriter, true);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
                .headers(Customizer.withDefaults());
        return http.build();
    }
}
