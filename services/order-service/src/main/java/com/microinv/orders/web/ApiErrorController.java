package com.microinv.orders.web;

import com.microinv.orders.error.ErrorCode;
import com.microinv.orders.error.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Errores que llegan al despacho de error del contenedor ({@code /error}) en el formato comun (seccion 5), en
 * lugar de la pagina por defecto de Spring. Mensajes genericos: no se ecoa nada de la peticion.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping(value = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = raw instanceof Integer value ? HttpStatus.resolve(value) : null;
        if (status == null || !(status.is4xxClientError() || status.is5xxServerError())) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ErrorCode code;
        if (status == HttpStatus.NOT_FOUND) {
            code = ErrorCode.NOT_FOUND;
        } else if (status == HttpStatus.UNAUTHORIZED) {
            code = ErrorCode.UNAUTHORIZED;
        } else if (status.is5xxServerError()) {
            code = ErrorCode.INTERNAL_ERROR;
        } else {
            code = ErrorCode.VALIDATION_ERROR;
        }
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String message = status.is5xxServerError() ? "Unexpected error" : status.getReasonPhrase();
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(status.value(), code, message, uri instanceof String s ? s : "", null));
    }
}
