package com.microinv.notifications.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce todas las excepciones al formato de error común (§5). Los mensajes son genéricos: sin trazas,
 * SQL ni nombres de clases; el detalle técnico solo va al log del servidor.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        return respond(ex.getStatus().value(), ex.getCode(), ex.getMessage(), ex.getDetails(), new HttpHeaders(),
                request);
    }

    /** Las excepciones de seguridad las resuelve el filtro de Spring Security, no este handler genérico. */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void rethrowSecurityException(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception while processing request", ex);
        return respond(500, ErrorCodes.INTERNAL_ERROR, ErrorCodes.messageForStatus(500), null, new HttpHeaders(),
                request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        int status = statusCode.value();
        if (status >= 500) {
            log.error("Server error while processing request", ex);
        }
        return respond(status, ErrorCodes.forStatus(status), ErrorCodes.messageForStatus(status), null, headers,
                request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiError.Detail> details = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            details.add(new ApiError.Detail(error.getField(), safeMessage(error.getDefaultMessage())));
        }
        return validationError(details, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        return validationError(List.of(), headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiError.Detail> details = new ArrayList<>();
        if (ex.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            String field = mapping.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            details.add(new ApiError.Detail(field, "invalid value"));
        } else {
            details.add(new ApiError.Detail("body", "malformed or unreadable JSON"));
        }
        return validationError(details, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String field = ex.getPropertyName() != null ? ex.getPropertyName() : "parameter";
        return validationError(List.of(new ApiError.Detail(field, "invalid value")), headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return validationError(List.of(new ApiError.Detail(ex.getParameterName(), "is required")), headers, request);
    }

    private ResponseEntity<Object> validationError(List<ApiError.Detail> details, HttpHeaders headers,
                                                   WebRequest request) {
        return respond(HttpStatus.BAD_REQUEST.value(), ErrorCodes.VALIDATION_ERROR,
                ErrorCodes.messageForStatus(400), details, headers, request);
    }

    private ResponseEntity<Object> respond(int status, String code, String message, List<ApiError.Detail> details,
                                           HttpHeaders sourceHeaders, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (sourceHeaders != null) {
            headers.putAll(sourceHeaders);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        String path = request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI() : "";
        return new ResponseEntity<>(ApiError.of(status, code, message, path, details), headers,
                HttpStatusCode.valueOf(status));
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "invalid value" : message;
    }
}
