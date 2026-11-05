package com.microinv.orders.client;

/**
 * Puerto hacia notification-service. Best-effort: quien lo invoca debe capturar cualquier excepcion;
 * una notificacion fallida jamas puede romper la operacion principal.
 */
public interface NotificationClient {

    /** Envia la notificacion; lanza una excepcion si no se pudo entregar. */
    void send(NotificationRequest request);
}
