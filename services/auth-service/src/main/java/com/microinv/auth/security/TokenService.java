package com.microinv.auth.security;

import com.microinv.auth.config.JwtProperties;
import com.microinv.auth.user.UserAccount;
import com.microinv.auth.web.AuthResponse;
import com.microinv.auth.web.UserResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Emite JWT HS256 con los claims del contrato (seccion 3): iss, sub, username, iat, exp. */
@Service
public class TokenService {

    public static final String USERNAME_CLAIM = "username";

    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtProperties props, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    public AuthResponse issue(UserAccount user) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Duration ttl = Duration.ofMinutes(props.expirationMinutes());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .claim(USERNAME_CLAIM, user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AuthResponse(token, "Bearer", ttl.toSeconds(), UserResponse.from(user));
    }
}
