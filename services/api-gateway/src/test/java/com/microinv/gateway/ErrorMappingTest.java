package com.microinv.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.microinv.gateway.error.ErrorCode;
import com.microinv.gateway.error.ErrorMapping;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Mapa excepcion -> (status, codigo del contrato) del gateway. */
class ErrorMappingTest {

    @Test
    void sinRuta_404_NOT_FOUND() {
        ErrorMapping.Failure f = ErrorMapping.classify(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThat(f.status().value()).isEqualTo(404);
        assertThat(f.code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void destinoCaidoOLento_503_SERVICE_UNAVAILABLE() {
        for (Throwable t : new Throwable[] {
                new ConnectException("Connection refused: auth-service/10.0.0.1:8081"),
                new TimeoutException("Response took longer than timeout"),
                new IOException("Connection prematurely closed"),
                new RuntimeException("wrapper", new ConnectException("refused")),
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT),
                new ResponseStatusException(HttpStatus.BAD_GATEWAY),
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)}) {
            ErrorMapping.Failure f = ErrorMapping.classify(t);
            assertThat(f.status().value()).as(t.toString()).isEqualTo(503);
            assertThat(f.code()).as(t.toString()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    void otros4xx_conservanElStatus_conVALIDATION_ERROR() {
        ErrorMapping.Failure bad = ErrorMapping.classify(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        assertThat(bad.status().value()).isEqualTo(400);
        assertThat(bad.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);

        ErrorMapping.Failure big = ErrorMapping.classify(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThat(big.status().value()).isEqualTo(413);
        assertThat(big.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);

        ErrorMapping.Failure unauthorized = ErrorMapping.classify(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThat(unauthorized.code()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void errorInesperado_500_INTERNAL_ERROR() {
        for (Throwable t : new Throwable[] {new IllegalStateException("boom"), new NullPointerException(),
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR)}) {
            ErrorMapping.Failure f = ErrorMapping.classify(t);
            assertThat(f.status().value()).isEqualTo(500);
            assertThat(f.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Test
    void causasCiclicas_noCuelganElClasificador() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(ErrorMapping.classify(a).code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
