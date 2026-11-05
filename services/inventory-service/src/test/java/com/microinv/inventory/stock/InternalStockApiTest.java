package com.microinv.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microinv.inventory.support.AbstractIntegrationTest;
import com.microinv.inventory.support.TestSecrets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Contrato HTTP de /internal/stock/* (con X-Internal-Key). */
class InternalStockApiTest extends AbstractIntegrationTest {

    private MockHttpServletRequestBuilder internal(String path, String body) {
        return post(path).header("X-Internal-Key", TestSecrets.INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String reserveBody(UUID orderRef, String... lines) {
        return "{\"orderRef\":\"" + orderRef + "\",\"items\":[" + String.join(",", lines) + "]}";
    }

    private static String line(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }

    @Test
    void reserveReturnsTheContractSnapshot() throws Exception {
        UUID p = insertProduct("API-1", 10, 2);
        UUID order = UUID.randomUUID();

        MvcResult result = mvc.perform(internal("/internal/stock/reserve", reserveBody(order, line(p, 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productId").value(p.toString()))
                .andExpect(jsonPath("$.items[0].sku").value("API-1"))
                .andExpect(jsonPath("$.items[0].name").value("Producto API-1"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(10.5))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("\"unitPrice\":10.50");
        assertThat(stockOf(p)).isEqualTo(7);
    }

    @Test
    void reserveIsIdempotentOverHttp() throws Exception {
        UUID p = insertProduct("API-2", 10, 2);
        String body = reserveBody(UUID.randomUUID(), line(p, 4));

        String first = mvc.perform(internal("/internal/stock/reserve", body)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(internal("/internal/stock/reserve", body)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(stockOf(p)).isEqualTo(6);
    }

    @Test
    void insufficientStockReturns409WithDetailsForEveryMissingProduct() throws Exception {
        UUID ok = insertProduct("API-3A", 10, 2);
        UUID b = insertProduct("API-3B", 1, 2);
        UUID c = insertProduct("API-3C", 0, 2);

        mvc.perform(internal("/internal/stock/reserve",
                        reserveBody(UUID.randomUUID(), line(ok, 2), line(b, 5), line(c, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.path").value("/internal/stock/reserve"))
                .andExpect(jsonPath("$.details", hasSize(2)))
                .andExpect(jsonPath("$.details[?(@.productId=='" + b + "')].requested").value(hasItem(5)))
                .andExpect(jsonPath("$.details[?(@.productId=='" + b + "')].available").value(hasItem(1)))
                .andExpect(jsonPath("$.details[?(@.productId=='" + c + "')].requested").value(hasItem(1)))
                .andExpect(jsonPath("$.details[?(@.productId=='" + c + "')].available").value(hasItem(0)));
        assertThat(stockOf(ok)).isEqualTo(10);
    }

    @Test
    void unknownProductReturns404AndInactiveReturns409() throws Exception {
        UUID inactive = insertProduct("API-4", 10, 2);
        jdbc.update("update products set active = false where id = ?", inactive);

        UUID missing = UUID.randomUUID();
        // NOT_FOUND y PRODUCT_INACTIVE comparten forma: details:[{productId, message}]
        mvc.perform(internal("/internal/stock/reserve", reserveBody(UUID.randomUUID(), line(missing, 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.details", hasSize(1)))
                .andExpect(jsonPath("$.details[0].productId").value(missing.toString()))
                .andExpect(jsonPath("$.details[0].message").value("Product not found"));
        mvc.perform(internal("/internal/stock/reserve", reserveBody(UUID.randomUUID(), line(inactive, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"))
                .andExpect(jsonPath("$.details", hasSize(1)))
                .andExpect(jsonPath("$.details[0].productId").value(inactive.toString()))
                .andExpect(jsonPath("$.details[0].message").value("Product is inactive"));
    }

    @Test
    void reserveOfAnAlreadyReleasedOrderRefIs409InvalidStatusTransition() throws Exception {
        UUID p = insertProduct("API-4B", 10, 2);
        UUID order = UUID.randomUUID();
        // la compensacion llega antes que la reserva: lapida
        mvc.perform(internal("/internal/stock/release", "{\"orderRef\":\"" + order + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(internal("/internal/stock/reserve", reserveBody(order, line(p, 3))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    void reserveValidatesTheRequestBody() throws Exception {
        UUID p = insertProduct("API-5", 10, 2);
        UUID order = UUID.randomUUID();
        String[] invalid = {
                reserveBody(order, line(p, 0)),                                   // cantidad 0
                reserveBody(order, line(p, -1)),                                  // cantidad negativa
                reserveBody(order),                                               // sin lineas
                reserveBody(order, line(p, 1), line(p, 2)),                       // productId repetido
                "{\"items\":[" + line(p, 1) + "]}",                               // sin orderRef
                "{\"orderRef\":\"no-es-uuid\",\"items\":[" + line(p, 1) + "]}",   // orderRef invalido
                "{\"orderRef\":\"" + order + "\"}",                               // sin items
                "{\"orderRef\":\"" + order + "\",\"items\":[null]}",              // linea nula
                "{\"orderRef\":\"" + order + "\",\"items\":[{\"quantity\":1}]}",  // sin productId
                "{\"orderRef\":\"" + order + "\",\"items\":[{\"productId\":\"" + p + "\"}]}", // sin cantidad
                "{\"orderRef\":\"" + order + "\",\"items\":[{\"productId\":\"" + p + "\",\"quantity\":1.5}]}",
                "not json"
        };
        for (String body : invalid) {
            mvc.perform(internal("/internal/stock/reserve", body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(content().string(not(containsString("Exception"))));
        }
        assertThat(stockOf(p)).isEqualTo(10);
        assertThat(countAllMovements()).isZero();
    }

    @Test
    void reserveRejectsMoreThan50Lines() throws Exception {
        String[] lines = new String[51];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = line(UUID.randomUUID(), 1);
        }
        mvc.perform(internal("/internal/stock/reserve", reserveBody(UUID.randomUUID(), lines)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void releaseReturns204AndRestoresStock() throws Exception {
        UUID p = insertProduct("API-6", 10, 2);
        UUID order = UUID.randomUUID();
        mvc.perform(internal("/internal/stock/reserve", reserveBody(order, line(p, 4)))).andExpect(status().isOk());

        mvc.perform(internal("/internal/stock/release", "{\"orderRef\":\"" + order + "\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(stockOf(p)).isEqualTo(10);

        // doble liberacion y liberacion de algo nunca reservado: 204 sin efecto
        mvc.perform(internal("/internal/stock/release", "{\"orderRef\":\"" + order + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(internal("/internal/stock/release", "{\"orderRef\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    void releaseValidatesTheBody() throws Exception {
        mvc.perform(internal("/internal/stock/release", "{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(internal("/internal/stock/release", "{\"orderRef\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(internal("/internal/stock/release", "")).andExpect(status().isBadRequest());
    }
}
