package com.microinv.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.microinv.auth.web.LoginRequest;
import com.microinv.auth.web.NormalizedMaxLength;
import com.microinv.auth.web.NormalizedMaxLengthValidator;
import com.microinv.auth.web.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Validacion pura (sin Spring): la contrasena se mide en BYTES UTF-8 (8-72) y el email en ASCII. */
class Utf8BytesValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalidFields(String email, String password) {
        return validator.validate(new RegisterRequest("valid_user", email, password)).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private Set<String> invalidFields(String password) {
        return invalidFields("valid@example.com", password);
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
        // 24 x euro (3 bytes) = 72 bytes; 40 caracteres = 120 bytes.
        assertThat(invalidFields("€".repeat(24))).isEmpty();
        assertThat(invalidFields("€".repeat(25))).containsExactly("password");
        assertThat(invalidFields("€".repeat(40))).containsExactly("password");
    }

    @Test
    void minimoDe8_tambienEsEnBytes() {
        assertThat(invalidFields("1234567")).containsExactly("password"); // 7 bytes
        assertThat(invalidFields("12345678")).isEmpty(); // 8 bytes
        assertThat(invalidFields("€€")).containsExactly("password"); // 6 bytes
        assertThat(invalidFields("€€ab")).isEmpty(); // 8 bytes con solo 4 caracteres
    }

    @Test
    void elMensajeDeValidacionDejaClaroQueSonBytes() {
        Set<ConstraintViolation<RegisterRequest>> violations =
                validator.validate(new RegisterRequest("valid_user", "valid@example.com", "€".repeat(40)));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("8").contains("72").contains("bytes").doesNotContain("€");

        Set<ConstraintViolation<LoginRequest>> login =
                validator.validate(new LoginRequest("someone", "a".repeat(73)));
        assertThat(login).hasSize(1);
        assertThat(login.iterator().next().getMessage()).contains("72").contains("bytes");
    }

    @Test
    void email_soloAsciiImprimible() {
        assertThat(invalidFields("ok.user+tag@example.com", "12345678")).isEmpty();
        for (String bad : new String[] {"josé@example.com", "user@exämple.com", "İx@example.com",
                "ıx@example.com", "a b@example.com", "\"a b\"@example.com", "a\tb@example.com"}) {
            assertThat(invalidFields(bad, "12345678")).as(bad).containsExactly("email");
        }
    }

    @Test
    void email_laLongitudSeMideTambienTrasNormalizar() {
        NormalizedMaxLengthValidator v = new NormalizedMaxLengthValidator();
        v.initialize(new NormalizedMaxLength() {
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return NormalizedMaxLength.class; }
            @Override public String message() { return ""; }
            @Override public Class<?>[] groups() { return new Class<?>[0]; }
            @SuppressWarnings("unchecked")
            @Override public Class<? extends jakarta.validation.Payload>[] payload() { return new Class[0]; }
            @Override public int value() { return 120; }
        });
        String ascii120 = "a".repeat(120);
        String dotted120 = "İ" + "a".repeat(119); // 120 caracteres; en minusculas son 121
        assertThat(dotted120).hasSize(120);
        assertThat(v.isValid(ascii120, null)).isTrue();
        assertThat(v.isValid(dotted120, null)).isFalse();
        assertThat(v.isValid(null, null)).isTrue();
    }

    @Test
    void toStringDeLosDtos_ocultaLaContrasena() {
        assertThat(new RegisterRequest("u", "e@example.com", "secret-password").toString()).doesNotContain("secret-password");
        assertThat(new LoginRequest("u", "secret-password").toString()).doesNotContain("secret-password");
    }
}
