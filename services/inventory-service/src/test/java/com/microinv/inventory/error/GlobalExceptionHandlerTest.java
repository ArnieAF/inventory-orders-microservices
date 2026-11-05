package com.microinv.inventory.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.context.request.ServletWebRequest;

/** El manejador de errores nunca debe filtrar detalles internos (SQL, clases, mensajes de excepcion). */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest("POST", "/api/x"));

    private static ApiError bodyOf(ResponseEntity<Object> response) {
        assertThat(response.getBody()).isInstanceOf(ApiError.class);
        return (ApiError) response.getBody();
    }

    @Test
    void unexpectedExceptionsBecomeAGeneric500WithoutLeakingTheMessage() {
        ResponseEntity<Object> response = handler.handleUnexpected(
                new IllegalStateException("select * from products where password = 'hunter2'"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiError body = bodyOf(response);
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).isEqualTo("Unexpected error");
        assertThat(body.path()).isEqualTo("/api/x");
        assertThat(body.toString()).doesNotContain("hunter2").doesNotContain("select").doesNotContain("IllegalState");
    }

    @Test
    void transientConcurrencyFailuresBecome503WithRetryAfter() {
        for (org.springframework.dao.ConcurrencyFailureException ex : List.of(
                new CannotAcquireLockException("lock timeout on relation products"),
                new DeadlockLoserDataAccessException("deadlock detected", null),
                new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID()))) {
            ResponseEntity<Object> response = handler.handleConcurrencyFailure(ex, request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
            ApiError body = bodyOf(response);
            assertThat(body.code()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(body.toString()).doesNotContain("relation").doesNotContain("deadlock");
        }
    }

    @Test
    void apiExceptionKeepsItsCodeStatusAndDetails() {
        UUID product = UUID.randomUUID();
        ResponseEntity<Object> response = handler.handleApi(
                ApiException.insufficientStock(List.of(new ApiException.StockShortage(product, 5, 2))), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiError body = bodyOf(response);
        assertThat(body.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.details()).asList().containsExactly(new ApiException.StockShortage(product, 5, 2));
    }

    @Test
    void dataIntegrityViolationsAreNotExposed() {
        ResponseEntity<Object> response = handler.handleUnexpected(
                new DataIntegrityViolationException("duplicate key value violates unique constraint uq_products_sku"),
                request);
        assertThat(bodyOf(response).toString()).doesNotContain("uq_products_sku").doesNotContain("duplicate key");
    }
}
