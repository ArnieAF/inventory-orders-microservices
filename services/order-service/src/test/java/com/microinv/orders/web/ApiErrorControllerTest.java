package com.microinv.orders.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.microinv.orders.error.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Errores del despacho de error del contenedor (/error) en el formato comun, sin la pagina por defecto. */
class ApiErrorControllerTest {

    private final ApiErrorController controller = new ApiErrorController();

    private ResponseEntity<ErrorResponse> dispatch(Object status, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        if (status != null) {
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        }
        if (uri != null) {
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, uri);
        }
        return controller.error(request);
    }

    @ParameterizedTest(name = "HTTP {0} -> {1} / {2}")
    @CsvSource({
            "400, VALIDATION_ERROR, Bad Request",
            "401, UNAUTHORIZED, Unauthorized",
            "404, NOT_FOUND, Not Found",
            "405, VALIDATION_ERROR, Method Not Allowed",
            "415, VALIDATION_ERROR, Unsupported Media Type",
            "500, INTERNAL_ERROR, Unexpected error",
            "503, INTERNAL_ERROR, Unexpected error"})
    void mapsContainerErrorsToTheCommonFormat(int status, String code, String message) {
        ResponseEntity<ErrorResponse> response = dispatch(status, "/api/orders/x");

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        ErrorResponse body = response.getBody();
        assertThat(body.status()).isEqualTo(status);
        assertThat(body.code()).isEqualTo(code);
        assertThat(body.message()).isEqualTo(message);
        assertThat(body.path()).isEqualTo("/api/orders/x");
        assertThat(body.timestamp()).isNotNull();
        assertThat(body.details()).isNull();
    }

    @Test
    void anythingUnexpectedBecomesAGeneric500() {
        for (Object weird : new Object[] {null, "404", 200, 302, 999}) {
            ResponseEntity<ErrorResponse> response = dispatch(weird, null);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().path()).isEmpty();
        }
    }
}
