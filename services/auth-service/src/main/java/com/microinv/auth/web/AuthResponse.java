package com.microinv.auth.web;

/** AuthResponse del contrato; expiresIn en segundos. */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

    @Override
    public String toString() {
        return "AuthResponse[accessToken=***, tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", user=" + user + "]";
    }
}
