package com.microinv.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Cliente HTTP minimo por socket: envia la ruta EXACTA (sin que ningun cliente la normalice). */
final class RawHttp {

    record Response(int status, String body) {
    }

    private RawHttp() {
    }

    static Response get(int port, String rawPath, String... headers) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            StringBuilder req = new StringBuilder("GET ").append(rawPath).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n")
                    .append("Connection: close\r\n");
            for (String header : headers) {
                req.append(header).append("\r\n");
            }
            req.append("\r\n");
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            ByteArrayOutputStream all = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                all.write(buf, 0, n);
            }
            String text = all.toString(StandardCharsets.UTF_8);
            int statusStart = text.indexOf(' ') + 1;
            int status = Integer.parseInt(text.substring(statusStart, statusStart + 3));
            int bodyStart = text.indexOf("\r\n\r\n");
            return new Response(status, bodyStart < 0 ? "" : text.substring(bodyStart + 4));
        }
    }
}
