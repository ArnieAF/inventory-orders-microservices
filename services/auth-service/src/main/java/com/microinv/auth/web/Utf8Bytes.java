package com.microinv.auth.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Longitud en BYTES UTF-8, no en caracteres (contrato 6.1: password 8-72 bytes). BCrypt solo admite
 * 72 bytes; con caracteres multibyte 40 caracteres ya pueden superarlos. No se normaliza (NFC/NFKC):
 * la contrasena se usa tal cual.
 */
@Documented
@Constraint(validatedBy = Utf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8Bytes {

    String message() default "must be between {min} and {max} bytes when UTF-8 encoded (bytes, not characters)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int min() default 0;

    int max() default Integer.MAX_VALUE;
}
