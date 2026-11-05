package com.microinv.gateway.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.util.StringUtils;

/** Validacion JWT HS256 en el gateway: firma, exp/nbf, iss y presencia de sub. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    public static final int MIN_SECRET_LENGTH = 32;

    /**
     * Valida la configuracion JWT ANTES de crear ningun bean (BeanFactoryPostProcessor): asi un
     * JWT_SECRET ausente o debil es el primer error visible del arranque.
     */
    @Bean
    static BeanFactoryPostProcessor jwtConfigurationValidator(Environment environment) {
        return beanFactory -> buildKey(new JwtProperties(environment.getProperty("app.jwt.secret"),
                environment.getProperty("app.jwt.issuer")));
    }

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(JwtProperties props) {
        SecretKey key = buildKey(props);
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256) // rechaza otros algoritmos (incluido alg=none)
                .build();
        OAuth2TokenValidator<Jwt> subjectPresent = jwt -> StringUtils.hasText(jwt.getSubject())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing subject", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer()), subjectPresent));
        return decoder;
    }

    /** Valida la configuracion y construye la clave. Falla de forma clara, sin revelar el secreto. */
    public static SecretKey buildKey(JwtProperties props) {
        String secret = props.secret();
        if (secret == null || secret.isBlank()) {
            throw new InvalidJwtConfigurationException(
                    "JWT_SECRET no esta definido: el gateway no arranca sin el secreto para validar tokens.");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new InvalidJwtConfigurationException(
                    "JWT_SECRET es demasiado corto (" + secret.length() + " caracteres): se exigen al menos "
                            + MIN_SECRET_LENGTH + ".");
        }
        if (!StringUtils.hasText(props.issuer())) {
            throw new InvalidJwtConfigurationException("JWT_ISSUER no puede estar vacio.");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
