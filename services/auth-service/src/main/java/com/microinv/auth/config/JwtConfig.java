package com.microinv.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/** Clave HS256 compartida por el emisor (JwtEncoder) y el validador (JwtDecoder) del resource server. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtProperties.class, RegistrationProperties.class})
public class JwtConfig {

    public static final int MIN_SECRET_LENGTH = 32;
    static final int MAX_EXPIRATION_MINUTES = 10_080; // 7 dias

    /**
     * Valida la configuracion JWT ANTES de crear ningun bean (BeanFactoryPostProcessor): asi un
     * JWT_SECRET ausente o debil es el primer y unico error visible, aunque la BD tampoco responda.
     */
    @Bean
    static BeanFactoryPostProcessor jwtConfigurationValidator(Environment environment) {
        return beanFactory -> {
            String minutes = environment.getProperty("app.jwt.expiration-minutes");
            int expiration;
            try {
                expiration = minutes == null || minutes.isBlank() ? 60 : Integer.parseInt(minutes.trim());
            } catch (NumberFormatException e) {
                throw new InvalidJwtConfigurationException("JWT_EXPIRATION_MINUTES debe ser un numero entero.");
            }
            buildKey(new JwtProperties(environment.getProperty("app.jwt.secret"),
                    environment.getProperty("app.jwt.issuer"), expiration));
        };
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecretKey jwtSecretKey(JwtProperties props) {
        return buildKey(props);
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> subjectPresent = jwt -> StringUtils.hasText(jwt.getSubject())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing subject", null));
        // Valida exp/nbf (con la tolerancia por defecto de 60 s), iss y presencia de sub.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer()), subjectPresent));
        return decoder;
    }

    /** Valida la configuracion y construye la clave. Falla de forma clara, sin revelar el secreto. */
    public static SecretKey buildKey(JwtProperties props) {
        String secret = props.secret();
        if (secret == null || secret.isBlank()) {
            throw new InvalidJwtConfigurationException(
                    "JWT_SECRET no esta definido: el servicio no arranca sin secreto de firma.");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new InvalidJwtConfigurationException(
                    "JWT_SECRET es demasiado corto (" + secret.length() + " caracteres): se exigen al menos "
                            + MIN_SECRET_LENGTH + ".");
        }
        if (!StringUtils.hasText(props.issuer())) {
            throw new InvalidJwtConfigurationException("JWT_ISSUER no puede estar vacio.");
        }
        if (props.expirationMinutes() < 1 || props.expirationMinutes() > MAX_EXPIRATION_MINUTES) {
            throw new InvalidJwtConfigurationException(
                    "JWT_EXPIRATION_MINUTES debe estar entre 1 y " + MAX_EXPIRATION_MINUTES + ".");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
