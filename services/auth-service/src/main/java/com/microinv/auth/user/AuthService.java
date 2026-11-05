package com.microinv.auth.user;

import com.microinv.auth.common.ApiException;
import com.microinv.auth.common.ErrorCode;
import com.microinv.auth.security.TokenService;
import com.microinv.auth.web.AuthResponse;
import com.microinv.auth.web.LoginRequest;
import com.microinv.auth.web.RegisterRequest;
import com.microinv.auth.web.UserResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Registro y login. Sin @Transactional a nivel de servicio a proposito: cada operacion del
 * repositorio es su propia transaccion, de modo que tras un conflicto de unicidad se puede
 * consultar de nuevo para decidir el codigo de error.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final Clock clock;

    /**
     * Hash BCrypt de una contrasena aleatoria, calculado con el mismo codificador (mismo coste).
     * Se compara contra el cuando el usuario no existe para que el login tarde parecido.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.clock = clock;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.dummyHash = passwordEncoder.encode(Base64.getEncoder().encodeToString(random));
    }

    public AuthResponse register(RegisterRequest request) {
        String username = Normalizer.lower(request.username());
        String email = Normalizer.lower(request.email());

        if (users.existsByUsername(username)) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN);
        }
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }

        UserAccount user = new UserAccount(
                UUID.randomUUID(),
                username,
                email,
                passwordEncoder.encode(request.password()),
                Instant.now(clock).truncatedTo(ChronoUnit.MILLIS));
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException race) {
            // Registro concurrente que gano la carrera: los UNIQUE de la BD son la garantia real.
            if (users.existsByUsername(username)) {
                throw new ApiException(ErrorCode.USERNAME_TAKEN);
            }
            if (users.existsByEmail(email)) {
                throw new ApiException(ErrorCode.EMAIL_TAKEN);
            }
            throw race; // otra violacion de integridad: sera un 500 generico
        }
        return tokens.issue(user);
    }

    public AuthResponse login(LoginRequest request) {
        Optional<UserAccount> found = users.findByUsername(Normalizer.lower(request.username()));
        // Siempre se ejecuta una comparacion BCrypt, exista o no el usuario.
        String hash = found.map(UserAccount::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (found.isEmpty() || !passwordMatches) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return tokens.issue(found.get());
    }

    public UserResponse findCurrent(UUID id) {
        return users.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
