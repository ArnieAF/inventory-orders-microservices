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

        @NotBlank(message = "is required")
        @Size(max = 120, message = "must be at most 120 characters")
        @Email(message = "must be a valid email address")
        String email,

        @NotNull(message = "is required")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        @MaxUtf8Bytes(72)
        String password) {

    @Override
    public String toString() {
        // Nunca volcar la contrasena en logs.
        return "RegisterRequest[username=" + username + ", email=" + email + ", password=***]";
    }
}
