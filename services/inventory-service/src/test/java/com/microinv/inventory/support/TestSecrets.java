package com.microinv.inventory.support;

/**
 * Valores de prueba (obviamente falsos, solo para tests). Son constantes de compilacion porque deben
 * llegar al Environment ANTES de crear el contexto (SecurityConfigurationPostProcessor los valida al
 * arrancar), lo que descarta @DynamicPropertySource.
 */
public final class TestSecrets {

    public static final String JWT_SECRET = "test-only-jwt-secret-0123456789-abcdefghijklmnopqrstuvwxyz";
    public static final String JWT_ISSUER = "micro-inventory";
    public static final String INTERNAL_KEY = "test-only-internal-key-0123456789";

    private TestSecrets() {
    }
}
