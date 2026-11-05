package com.microinv.orders.web;

import com.microinv.orders.error.ApiException;
import com.microinv.orders.error.ErrorCode;
import com.microinv.orders.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce toda excepcion al formato comun de error (contrato, seccion 5). Los mensajes son genericos: ni trazas
 * de pila, ni SQL, ni nombres de clases, ni valores recibidos.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final int MAX_DETAILS = 20;

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        if (ex.status().is5xxServerError()) {
            log.warn("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), ex.status().value(), ex.code());
        }
        return build(ex.status(), ex.code(), ex.getMessage(), request, ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex,
                                                       HttpServletRequest request) {
        List<Map<String, Object>> details = ex.getBindingResult().getAllErrors().stream()
                .map(error -> detail(
                        error instanceof org.springframework.validation.FieldError fe ? fe.getField() : "body",
                        error.getDefaultMessage()))
                .sorted(Comparator.comparing((Map<String, Object> d) -> String.valueOf(d.get("field")))
                        .thenComparing(d -> String.valueOf(d.get("message"))))
                .limit(MAX_DETAILS)
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Validation failed", request, details);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> handleOtherValidation(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Validation failed", request, null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class})
    ResponseEntity<ErrorResponse> handleMalformedRequest(Exception ex, HttpServletRequest request) {
        // Sin ecoar el valor recibido ni el mensaje del parser JSON.
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Malformed or invalid request", request,
                null);
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ErrorResponse> handleUnknownRoute(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Resource not found", request, null);
    }

    /**
     * Version optimista u otro conflicto de concurrencia (p. ej. interbloqueo): dos cambios simultaneos sobre
     * el mismo pedido; el perdedor recibe 409 INVALID_STATUS_TRANSITION.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ErrorResponse> handleConcurrency(ConcurrencyFailureException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_TRANSITION,
                "Order was modified concurrently; reload it and try again", request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication is required", request, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) throws Exception {
        if (ex instanceof AccessDeniedException) {
            throw ex; // lo resuelve Spring Security, no es un error 500
        }
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected error", request, null);
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, ErrorCode code, String message,
                                                       HttpServletRequest request,
                                                       List<Map<String, Object>> details) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), code, message, request.getRequestURI(), details));
    }

    private static Map<String, Object> detail(String field, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", field);
        detail.put("message", message);
        return detail;
    }
}
