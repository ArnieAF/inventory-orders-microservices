package com.microinv.inventory.error;

import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Convierte TODAS las excepciones al formato comun de error (contrato 5). Nunca expone trazas, SQL,
 * nombres de clases ni mensajes internos: el detalle queda solo en el log del servidor.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException ex, WebRequest request) {
        return build(ex.getCode().status(), ex.getCode(), ex.getMessage(), request, ex.getDetails());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldDetail> details = ex.getConstraintViolations().stream()
                .map(v -> new FieldDetail(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Validation failed", request, details);
    }

    /**
     * Fallos transitorios: la operacion NO se aplico y se puede reintentar (el stock queda intacto porque todo va
     * en una transaccion). Incluye interbloqueo perdido, timeout de bloqueo de fila (PostgreSQL
     * {@code lock_timeout}, SQLState 55P03), timeout de consulta o de transaccion (los 4 s de reserve/release),
     * bloqueo optimista y base de datos/pool no disponibles. 503 {@code SERVICE_UNAVAILABLE} con
     * {@code Retry-After}, en vez de 500, para que el llamante lo trate como "servicio temporalmente no disponible".
     */
    @ExceptionHandler({ConcurrencyFailureException.class, QueryTimeoutException.class,
            TransactionTimedOutException.class, CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class})
    public ResponseEntity<Object> handleTransientFailure(Exception ex, WebRequest request) {
        log.warn("Transient failure, returning 503 ({})", ex.getClass().getSimpleName());
        return serviceUnavailable(request);
    }

    private ResponseEntity<Object> serviceUnavailable(WebRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(HttpStatus.SERVICE_UNAVAILABLE.value(), ErrorCode.SERVICE_UNAVAILABLE.name(),
                        "Temporarily unavailable, please retry", pathOf(request), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        // El detalle (clase, SQL, traza) queda solo en el log del servidor.
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected error", request, null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldDetail> details = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.add(new FieldDetail(fe.getField(), fe.getDefaultMessage()));
        }
        for (ObjectError oe : ex.getBindingResult().getGlobalErrors()) {
            details.add(new FieldDetail(oe.getObjectName(), oe.getDefaultMessage()));
        }
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Validation failed", request, details);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Malformed request body", request, null);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String field = ex.getPropertyName() != null ? ex.getPropertyName() : "parameter";
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Validation failed", request,
                List.of(new FieldDetail(field, "invalid value")));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Validation failed", request,
                List.of(new FieldDetail(ex.getParameterName(), "is required")));
    }

    /** Resto de errores del framework (404 de rutas, 405, 415...): mismo formato, mensaje generico. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = statusCode.is5xxServerError() ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST;
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            // p. ej. AsyncRequestTimeoutException: es "no disponible", no un error interno.
            log.warn("Framework reported 503 ({})", ex.getClass().getSimpleName());
            return serviceUnavailable(request);
        }
        if (status.is5xxServerError()) {
            log.error("Framework error", ex);
            return build(status, ErrorCode.INTERNAL_ERROR, "Unexpected error", request, null);
        }
        return build(status, ErrorCode.forStatus(status), status.getReasonPhrase(), request, null);
    }

    private ResponseEntity<Object> build(HttpStatus status, ErrorCode code, String message, WebRequest request,
                                         List<?> details) {
        ApiError body = ApiError.of(status.value(), code.name(), message, pathOf(request), details);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "";
    }
}
