package com.microinv.inventory.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Errores a nivel de contenedor (/error) en el formato comun, sin la pagina por defecto de Spring. */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping(value = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int value = raw instanceof Integer i ? i : HttpStatus.INTERNAL_SERVER_ERROR.value();
        HttpStatus status = HttpStatus.resolve(value);
        if (status == null || !(status.is4xxClientError() || status.is5xxServerError())) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ErrorCode code = ErrorCode.forStatus(status);
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String message = switch (code) {
            case INTERNAL_ERROR -> "Unexpected error";
            case SERVICE_UNAVAILABLE -> "Temporarily unavailable, please retry";
            default -> status.getReasonPhrase();
        };
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(status.value(), code.name(), message, uri instanceof String s ? s : "", null));
    }
}
