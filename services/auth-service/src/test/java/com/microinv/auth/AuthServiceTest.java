package com.microinv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microinv.auth.common.ApiException;
import com.microinv.auth.common.ErrorCode;
import com.microinv.auth.security.TokenService;
import com.microinv.auth.user.AuthService;
import com.microinv.auth.user.Normalizer;
import com.microinv.auth.user.UserAccount;
import com.microinv.auth.user.UserRepository;
import com.microinv.auth.web.AuthResponse;
import com.microinv.auth.web.LoginRequest;
import com.microinv.auth.web.RegisterRequest;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Logica de AuthService aislada (repositorio y token simulados). */
class AuthServiceTest {

    private UserRepository users;
    private PasswordEncoder encoder;
    private TokenService tokens;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        tokens = mock(TokenService.class);
        when(encoder.encode(any())).thenReturn("$2a$10$hashed");
        service = new AuthService(users, encoder, tokens, Clock.systemUTC());
    }

    @Test
    void register_normalizaAMinusculasAntesDeGuardar() {
        when(tokens.issue(any())).thenReturn(mock(AuthResponse.class));

        service.register(new RegisterRequest("MiXeD.Name", "Mixed@Example.COM", "Sup3r-Secret-pw"));

        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("mixed.name");
        assertThat(saved.getValue().getEmail()).isEqualTo("mixed@example.com");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$10$hashed");
        verify(users).existsByUsername("mixed.name");
        verify(users).existsByEmail("mixed@example.com");
    }

    @Test
    void register_carreraDeUnicidad_traduceElConflictoSinFiltrarElError() {
        // El pre-chequeo no ve a nadie, pero la BD rechaza (otro registro concurrente gano).
        when(users.existsByUsername("racer")).thenReturn(false, true);
        when(users.existsByEmail("racer@example.com")).thenReturn(false);
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_users_username SQL..."));

        assertThatThrownBy(() -> service.register(new RegisterRequest("racer", "racer@example.com", "Sup3r-Secret-pw")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.USERNAME_TAKEN));
    }

    @Test
    void register_carreraDeUnicidadPorEmail_EMAIL_TAKEN() {
        when(users.existsByUsername("racer2")).thenReturn(false);
        when(users.existsByEmail("racer2@example.com")).thenReturn(false, true);
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> service.register(new RegisterRequest("racer2", "racer2@example.com", "Sup3r-Secret-pw")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_TAKEN));
    }

    @Test
    void register_otraViolacionDeIntegridad_seRelanza_y_noSeConvierteEnConflicto() {
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("check constraint"));

        assertThatThrownBy(() -> service.register(new RegisterRequest("alguien", "alguien@example.com", "Sup3r-Secret-pw")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void login_usuarioInexistente_comparaContraHashFalso_yFallaConElMismoCodigo() {
        when(users.findByUsername("nadie")).thenReturn(Optional.empty());
        when(encoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("Nadie", "x-password")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(encoder).matches(any(), hash.capture());
        assertThat(hash.getValue()).isEqualTo("$2a$10$hashed"); // el hash falso generado con el mismo encoder
        verify(tokens, never()).issue(any());
    }

    @Test
    void login_passwordIncorrecto_mismoCodigo() {
        UserAccount user = new UserAccount(java.util.UUID.randomUUID(), "bob", "bob@example.com", "$2a$10$real",
                java.time.Instant.now());
        when(users.findByUsername("bob")).thenReturn(Optional.of(user));
        when(encoder.matches("mala", "$2a$10$real")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("bob", "mala")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        verify(tokens, never()).issue(any());
    }

    @Test
    void normalizer_usaMinusculasIndependientesDelLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")); // "I".toLowerCase() daria "ı" con locale turco
            assertThat(Normalizer.lower("ADMIN-ID")).isEqualTo("admin-id");
            assertThat(Normalizer.lower(null)).isNull();
        } finally {
            Locale.setDefault(previous);
        }
    }
}
