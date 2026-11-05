package com.microinv.inventory.notification;

/**
 * Cliente hacia notification-service. Es una interfaz para poder simularlo en las pruebas.
 * Contrato de uso: es best-effort; la implementacion registra y descarta los fallos, y quien la llame
 * debe estar preparado para que, aun asi, lance una excepcion (nunca debe romper la operacion principal).
 */
public interface NotificationClient {

    void send(NotificationRequest request);
}
