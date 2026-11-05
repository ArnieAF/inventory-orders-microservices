package com.microinv.auth.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reemplaza a BasicErrorController: los errores que no pasan por Spring MVC (p. ej. peticiones
 * rechazadas por el contenedor) tambien salen en el formato comun.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int value = attr instanceof Integer i ? i : 404; // GET /error directo: se trata como inexistente
        HttpStatus status = HttpStatus.resolve(value);
        if (status == null) {
            value = 500;
        }
        ErrorCode code;
        String message;
        if (value == 404) {
            code = ErrorCode.NOT_FOUND;
            message = code.defaultMessage();
        } else if (value == 401) {
            code = ErrorCode.UNAUTHORIZED;
            message = code.defaultMessage();
        } else if (value >= 500) {
            code = ErrorCode.INTERNAL_ERROR;
            message = code.defaultMessage();
        } else {
            code = ErrorCode.VALIDATION_ERROR;
            message = status != null ? status.getReasonPhrase() : code.defaultMessage();
        }
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = uri instanceof String s ? s : request.getRequestURI();
        return ResponseEntity.status(value).contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(value, code.name(), message, path, null));
    }
}
