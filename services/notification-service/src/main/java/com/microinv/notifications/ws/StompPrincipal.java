package com.microinv.notifications.ws;

import java.security.Principal;

/** Principal de una sesión STOMP: {@code name} = {@code sub} del JWT (id de usuario en forma canónica). */
public record StompPrincipal(String name) implements Principal {

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "StompPrincipal";
    }
}
