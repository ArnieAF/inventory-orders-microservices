package com.microinv.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Cuerpo de POST /api/auth/register (contrato 6.1). */
public record RegisterRequest(
        @NotNull(message = "is required")
        @Size(min = 3, max = 32, message = "must be between 3 and 32 characters")
        @Pattern(regexp = "[a-zA-Z0-9_.-]*", message = "may only contain letters, digits, '_', '.' and '-'")
        String username,

        // Solo ASCII imprimible (sin espacios): evita diferencias entre LOWER() de PostgreSQL y
        // toLowerCase de Java con no-ASCII y que la normalizacion alargue el valor.
        @NotBlank(message = "is required")
        @Size(max = 120, message = "must be at most 120 characters")
        @Pattern(regexp = "[\\x21-\\x7E]*", message = "may only contain printable ASCII characters without spaces")
        @NormalizedMaxLength(120)
        @Email(message = "must be a valid email address")
        String email,

        // 8-72 BYTES UTF-8 (limite real de BCrypt), no caracteres. La contrasena no se normaliza.
        @NotNull(message = "is required")
        @Utf8Bytes(min = 8, max = 72)
        String password) {

    @Override
    public String toString() {
        // Nunca volcar la contrasena en logs.
        return "RegisterRequest[username=" + username + ", email=" + email + ", password=***]";
    }
}
