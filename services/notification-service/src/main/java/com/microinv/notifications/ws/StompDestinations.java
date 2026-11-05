package com.microinv.notifications.ws;

/**
 * Política de destinos STOMP (§7). El cliente solo puede suscribirse a dos destinos exactos; no existen destinos de
 * aplicación, así que no puede enviar nada. Comparación EXACTA (sin comodines, sin barras finales, sin mayúsculas
 * distintas), por lo que no se puede suscribir a la cola de otro usuario ({@code /user/<otro>/queue/...}).
 */
public final class StompDestinations {

    /** Difusión a todos los usuarios conectados. */
    public static final String BROADCAST_TOPIC = "/topic/notifications";

    /** Destino al que se suscribe el cliente para sus notificaciones dirigidas (Spring lo resuelve por sesión). */
    public static final String USER_QUEUE_SUBSCRIPTION = "/user/queue/notifications";

    /** Destino que usa el servidor con {@code convertAndSendToUser}. */
    public static final String USER_QUEUE = "/queue/notifications";

    private StompDestinations() {
    }

    public static boolean isSubscribable(String destination) {
        return BROADCAST_TOPIC.equals(destination) || USER_QUEUE_SUBSCRIPTION.equals(destination);
    }
}
