package com.microinv.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.microinv.auth.web.RegisterRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** El limite de contrasena de BCrypt es de 72 BYTES, no de caracteres. */
class MaxUtf8BytesValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalidFields(String password) {
        return validator.validate(new RegisterRequest("valid_user", "valid@example.com", password)).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void setentaYDosBytesAscii_esValido_setentaYTres_no() {
        assertThat(invalidFields("a".repeat(72))).isEmpty();
        assertThat(invalidFields("a".repeat(73))).containsExactly("password");
    }

    @Test
    void caracteresMultibyte_seMidenEnBytes() {
        // 36 x "é" (2 bytes) = 72 bytes -> valido; 37 -> 74 bytes -> invalido aunque tenga solo 37 caracteres.
        assertThat(invalidFields("é".repeat(36))).isEmpty();
        assertThat(invalidFields("é".repeat(37))).containsExactly("password");
        // 24 x euro (3 bytes) = 72 bytes.
        assertThat(invalidFields("€".repeat(24))).isEmpty();
        assertThat(invalidFields("€".repeat(25))).containsExactly("password");
    }

    @Test
    void passwordCorto_siguePidiendoMinimoDe8() {
        assertThat(invalidFields("1234567")).containsExactly("password");
        assertThat(invalidFields("12345678")).isEmpty();
    }

    @Test
    void toStringDeLosDtos_ocultaLaContrasena() {
        assertThat(new RegisterRequest("u", "e@example.com", "secret-password").toString()).doesNotContain("secret-password");
    }
}
