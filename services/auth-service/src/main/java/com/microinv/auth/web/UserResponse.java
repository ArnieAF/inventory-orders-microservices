package com.microinv.auth.web;

import com.microinv.auth.user.UserAccount;
import java.util.UUID;

/** User del contrato: {id, username, email}. */
public record UserResponse(UUID id, String username, String email) {

    public static UserResponse from(UserAccount user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail());
    }
}
