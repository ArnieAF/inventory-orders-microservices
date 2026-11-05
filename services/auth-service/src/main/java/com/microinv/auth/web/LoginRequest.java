package com.microinv.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de POST /api/auth/login. Solo se limita el tamano maximo (no se revelan las reglas de
 * registro) para acotar el trabajo de BCrypt y de la consulta.
 */
public record LoginRequest(
        @NotBlank(message = "is required")
        @Size(max = 32, message = "must be at most 32 characters")
        String username,

        @NotEmpty(message = "is required")
        @Size(max = 72, message = "must be at most 72 characters")
        @MaxUtf8Bytes(72)
        String password) {

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=***]";
    }
}
