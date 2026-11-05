package com.microinv.auth.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce toda excepcion al formato comun de error. Los detalles internos solo van al log,
 * nunca al cliente.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException ex, HttpServletRequest request) {
        return json(ApiError.of(ex.code(), request.getRequestURI()));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void rethrowSecurity(RuntimeException ex) {
        // Que los resuelva Spring Security (entry point / access denied handler) y no el catch-all.
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        // En ERROR solo clases y ruta: el mensaje de la excepcion (y la traza, que lo incluye) puede traer
        // SQL o valores rechazados (email, password). La traza completa queda solo en DEBUG.
        log.error("Unhandled error on {} {}: {} (root cause: {})", request.getMethod(), request.getRequestURI(),
                ex.getClass().getName(), rootCauseClass(ex));
        if (log.isDebugEnabled()) {
            log.debug("Stack trace of the unhandled error (may contain sensitive values)", ex);
        }
        return json(ApiError.of(ErrorCode.INTERNAL_ERROR, request.getRequestURI()));
    }

    /** Nombre de la clase de la causa raiz (profundidad acotada por si hay ciclos). */
    static String rootCauseClass(Throwable ex) {
        Throwable root = ex;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 16; depth++) {
            root = root.getCause();
        }
        return root.getClass().getName();
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiError.Detail> details = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new ApiError.Detail(
                        error instanceof FieldError fe ? fe.getField() : error.getObjectName(),
                        error.getDefaultMessage()))
                .toList();
        return json(ApiError.of(400, ErrorCode.VALIDATION_ERROR.name(), ErrorCode.VALIDATION_ERROR.defaultMessage(),
                path(request), details));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return json(ApiError.of(400, ErrorCode.VALIDATION_ERROR.name(), "Malformed request body", path(request), null));
    }

    /** Resto de excepciones estandar de Spring MVC (404, 405, 415, parametros...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        int value = statusCode.value();
        HttpStatus status = HttpStatus.resolve(value);
        String code;
        String message;
        if (value == 404) {
            code = ErrorCode.NOT_FOUND.name();
            message = ErrorCode.NOT_FOUND.defaultMessage();
        } else if (value == 503) {
            code = ErrorCode.SERVICE_UNAVAILABLE.name();
            message = ErrorCode.SERVICE_UNAVAILABLE.defaultMessage();
        } else if (value >= 500) {
            code = ErrorCode.INTERNAL_ERROR.name();
            message = ErrorCode.INTERNAL_ERROR.defaultMessage();
        } else {
            // Otros 4xx (405, 406, 415, parametros ausentes...): el contrato no define mas codigos.
            code = ErrorCode.VALIDATION_ERROR.name();
            message = status != null ? status.getReasonPhrase() : ErrorCode.VALIDATION_ERROR.defaultMessage();
        }
        ApiError error = ApiError.of(value, code, message, path(request), null);
        HttpHeaders out = new HttpHeaders();
        if (headers != null) {
            out.addAll(headers); // conserva cabeceras utiles como Allow en 405
        }
        out.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(value).headers(out).body(error);
    }

    private static ResponseEntity<Object> json(ApiError error) {
        return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_JSON).body(error);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }
}
