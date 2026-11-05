package com.microinv.inventory.support;

import java.util.UUID;

/** Secretos de prueba generados al azar en cada ejecucion: no hay ninguno escrito en el repositorio. */
public final class TestSecrets {

    public static final String JWT_SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();
    public static final String JWT_ISSUER = "micro-inventory";
    public static final String INTERNAL_KEY = "ik-" + UUID.randomUUID();

    private TestSecrets() {
    }
}
