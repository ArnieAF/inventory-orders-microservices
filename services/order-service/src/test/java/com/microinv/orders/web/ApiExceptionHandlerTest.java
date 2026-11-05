package com.microinv.orders.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.orders.error.ErrorResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

/** Formato comun de error (contrato, seccion 5) para las excepciones que no se provocan facilmente por HTTP. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/orders/x/status");

    @Test
    void optimisticAndOtherConcurrencyFailuresAre409InvalidStatusTransition() {
        for (RuntimeException failure : List.of(new OptimisticLockingFailureException("Row was updated by another tx"),
                new CannotAcquireLockException("could not obtain lock on table orders"))) {
            ResponseEntity<ErrorResponse> response = handler.handleConcurrency(
                    (org.springframework.dao.ConcurrencyFailureException) failure, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            ErrorResponse body = response.getBody();
            assertThat(body.status()).isEqualTo(409);
            assertThat(body.code()).isEqualTo("INVALID_STATUS_TRANSITION");
            assertThat(body.path()).isEqualTo("/api/orders/x/status");
            assertThat(body.timestamp()).isNotNull();
            assertThat(body.message()).doesNotContain("Row was").doesNotContain("table");
            assertThat(body.details()).isNull();
        }
    }

    @Test
    void unexpectedExceptionsAre500WithAGenericMessageAndNoInternals() throws Exception {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(
                new DataIntegrityViolationException("could not execute statement; SQL [insert into orders ...]"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = response.getBody();
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).isEqualTo("Unexpected error");
        assertThat(body.toString()).doesNotContain("SQL").doesNotContain("insert").doesNotContain("Exception");
    }

    @Test
    void accessDeniedIsLeftToSpringSecurity() {
        assertThatThrownBy(() -> handler.handleUnexpected(new AccessDeniedException("denied"), request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void authenticationErrorsAre401InCommonFormat() {
        ResponseEntity<ErrorResponse> response = handler.handleAuthentication(
                new BadCredentialsException("secret internals"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("UNAUTHORIZED");
        assertThat(response.getBody().message()).doesNotContain("secret");
    }

    @Test
    void errorResponseOmitsEmptyDetails() {
        assertThat(ErrorResponse.of(400, com.microinv.orders.error.ErrorCode.VALIDATION_ERROR, "m", "/p", List.of())
                .details()).isNull();
        assertThat(ErrorResponse.of(400, com.microinv.orders.error.ErrorCode.VALIDATION_ERROR, "m", "/p",
                List.of(Map.of("field", "f", "message", "x"))).details()).hasSize(1);
    }
}
