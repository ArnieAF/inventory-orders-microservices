package com.microinv.orders.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Utilidades comunes de los clientes REST hacia servicios internos. */
final class InternalHttp {

    static final String INTERNAL_KEY_HEADER = "X-Internal-Key";
    /** Tope de lectura del cuerpo de una respuesta interna (evita que un servicio defectuoso agote memoria). */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private InternalHttp() {
    }

    /** RestClient con timeouts de conexion y lectura y sin seguir redirecciones en POST. */
    static RestClient restClient(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    static byte[] readLimited(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        return in.readNBytes(MAX_BODY_BYTES);
    }

    /** {@code true} si el fallo ocurrio al conectar, es decir, la peticion nunca llego al servicio destino. */
    static boolean isConnectFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException) {
                return true;
            }
            if (t instanceof SocketTimeoutException
                    && t.getMessage() != null
                    && t.getMessage().toLowerCase(Locale.ROOT).contains("connect")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
