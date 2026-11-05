package com.microinv.notifications.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sustituye el {@code /error} por defecto de Spring Boot para que los errores que nacen fuera de MVC
 * (por ejemplo, rechazos del contenedor) también salgan en el formato común (§5).
 */
@RestController
public class CommonErrorController implements ErrorController {

    @RequestMapping(path = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer code && code >= 400 && code < 600 ? code : 500;
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = uri instanceof String value ? value : request.getRequestURI();
        return ResponseEntity.status(HttpStatusCode.valueOf(status)).body(ApiError.of(status, path));
    }
}
