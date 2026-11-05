package com.microinv.notifications.ws;

import org.springframework.messaging.MessagingException;

/**
 * Rechazo de un frame STOMP por seguridad. Su mensaje es el ÚNICO texto que llega al cliente en el frame
 * {@code ERROR} (genérico, sin detalles internos). No captura traza: los rechazos son un flujo normal y un
 * atacante no debe poder llenar el log de trazas.
 */
public class StompSecurityException extends MessagingException {

    public static final String AUTHENTICATION_REQUIRED = "Authentication required";
    public static final String INVALID_CREDENTIALS = "Invalid or expired credentials";
    public static final String ALREADY_CONNECTED = "Already connected";
    public static final String DESTINATION_NOT_ALLOWED = "Destination not allowed";
    public static final String OPERATION_NOT_ALLOWED = "Operation not allowed";

    public StompSecurityException(String publicMessage) {
        super(publicMessage);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
