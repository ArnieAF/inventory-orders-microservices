package com.microinv.notifications.ws;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Frame ERROR hacia el cliente sin detalles internos: solo el mensaje genérico de un
 * {@link StompSecurityException}; cualquier otro fallo (decodificación, límite de tamaño, etc.) sale como
 * "Invalid message". El detalle técnico queda solo en el log del servidor.
 */
public class SafeStompErrorHandler extends StompSubProtocolErrorHandler {

    static final String GENERIC_MESSAGE = "Invalid message";

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        String publicMessage = GENERIC_MESSAGE;
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof StompSecurityException security) {
                publicMessage = security.getMessage();
                break;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return super.handleClientMessageProcessingError(clientMessage, new MessageDeliveryException(publicMessage));
    }
}
