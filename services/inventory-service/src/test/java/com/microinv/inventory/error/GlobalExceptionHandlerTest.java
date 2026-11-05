package com.microinv.inventory.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.hibernate.JDBCException;
import org.hibernate.dialect.PostgreSQLDialect;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/** El manejador de errores nunca debe filtrar detalles internos (SQL, clases, mensajes de excepcion). */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest("POST", "/api/x"));

    private static ApiError bodyOf(ResponseEntity<Object> response) {
        assertThat(response.getBody()).isInstanceOf(ApiError.class);
        return (ApiError) response.getBody();
    }

    private void assertServiceUnavailable(ResponseEntity<Object> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        ApiError body = bodyOf(response);
        assertThat(body.status()).isEqualTo(503);
        assertThat(body.code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(body.path()).isEqualTo("/api/x");
        assertThat(body.toString()).doesNotContain("relation").doesNotContain("deadlock").doesNotContain("lock timeout");
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
        for (ConcurrencyFailureException ex : List.of(
                new CannotAcquireLockException("lock timeout on relation products"),
                new DeadlockLoserDataAccessException("deadlock detected", null),
                new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID()))) {
            assertServiceUnavailable(handler.handleTransientFailure(ex, request));
        }
    }

    @Test
    void queryAndTransactionTimeoutsBecome503WithRetryAfter() {
        // los 4 s de reserve/release: timeout de consulta (cancelacion por el driver) o de transaccion (Spring)
        assertServiceUnavailable(handler.handleTransientFailure(new QueryTimeoutException("lock timeout"), request));
        assertServiceUnavailable(handler.handleTransientFailure(
                new TransactionTimedOutException("Transaction timed out: deadline was ..."), request));
        assertServiceUnavailable(handler.handleTransientFailure(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"), request));
    }

    @Test
    void frameworkReported503IsServiceUnavailableNotInternalError() {
        ResponseEntity<Object> response = handler.handleExceptionInternal(new AsyncRequestTimeoutException(), null,
                new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, request);
        assertServiceUnavailable(response);
    }

    @Test
    void otherFrameworkServerErrorsStayInternalError() {
        ResponseEntity<Object> response = handler.handleExceptionInternal(new IllegalStateException("x"), null,
                new HttpHeaders(), HttpStatus.BAD_GATEWAY, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(bodyOf(response).code()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void errorCodeMappingForContainerStatuses() {
        assertThat(ErrorCode.forStatus(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.forStatus(HttpStatus.BAD_GATEWAY)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.forStatus(HttpStatus.NOT_FOUND)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.forStatus(HttpStatus.UNAUTHORIZED)).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(ErrorCode.forStatus(HttpStatus.METHOD_NOT_ALLOWED)).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    /**
     * Cadena real de traduccion para PostgreSQL (sin necesitar un servidor): SQLState 55P03 (lock_timeout) y
     * 57014 (consulta cancelada por timeout) -> excepcion de Hibernate -> excepcion de Spring que el manejador
     * mapea a 503. Revisa que un lock_timeout de PostgreSQL NO acabe en 500.
     */
    @Test
    void postgresLockTimeoutAndQueryCancelTranslateTo503() {
        var delegate = new PostgreSQLDialect().buildSQLExceptionConversionDelegate();
        HibernateJpaDialect jpaDialect = new HibernateJpaDialect();

        JDBCException lockTimeout = delegate.convert(
                new SQLException("ERROR: canceling statement due to lock timeout", "55P03"), "lock", "select ...");
        JDBCException cancelled = delegate.convert(
                new SQLException("ERROR: canceling statement due to user request", "57014"), "query", "select ...");
        JDBCException deadlock = delegate.convert(
                new SQLException("ERROR: deadlock detected", "40P01"), "deadlock", "update ...");

        for (JDBCException hibernateException : List.of(lockTimeout, cancelled, deadlock)) {
            RuntimeException translated = jpaDialect.translateExceptionIfPossible(hibernateException);
            assertThat(translated).as("traduccion de %s", hibernateException.getClass().getSimpleName()).isNotNull();
            var method = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class).resolveMethodByThrowable(translated);
            assertThat(method).as("manejador de %s", translated.getClass().getSimpleName()).isNotNull();
            assertThat(method.getName()).isEqualTo("handleTransientFailure");
            assertServiceUnavailable(handler.handleTransientFailure(translated, request));
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
