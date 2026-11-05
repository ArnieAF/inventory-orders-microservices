package com.microinv.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microinv.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderQueryApiTest extends AbstractApiTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

    private UUID seed(OrderStatus status, String customer, String total, int items, int minutesAfterT0) {
        return seedOrder(status, USER_ID, USERNAME, customer, new BigDecimal(total), items,
                T0.plusSeconds(60L * minutesAfterT0));
    }

    // ---- listado ----------------------------------------------------------------------------------------------

    @Test
    void listsWithPaginationEnvelopeDefaultSortNewestFirstAndSummaryFields() throws Exception {
        UUID oldest = seed(OrderStatus.PENDING, "Alpha", "10.00", 1, 0);
        UUID middle = seed(OrderStatus.CONFIRMED, "Bravo", "20.50", 3, 1);
        UUID newest = seed(OrderStatus.SHIPPED, "Charlie", "5.00", 2, 2);

        mvc.perform(authed(get("/api/orders")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(newest.toString()))
                .andExpect(jsonPath("$.content[1].id").value(middle.toString()))
                .andExpect(jsonPath("$.content[2].id").value(oldest.toString()))
                .andExpect(jsonPath("$.content[0].customerName").value("Charlie"))
                .andExpect(jsonPath("$.content[0].status").value("SHIPPED"))
                .andExpect(jsonPath("$.content[0].itemCount").value(2))
                .andExpect(jsonPath("$.content[1].itemCount").value(3))
                .andExpect(jsonPath("$.content[0].createdByUsername").value(USERNAME))
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(content().string(containsString("\"total\":20.50")))
                // OrderSummary no lleva items ni notas
                .andExpect(jsonPath("$.content[0].items").doesNotExist());
    }

    @Test
    void filtersByStatus() throws Exception {
        seed(OrderStatus.PENDING, "A", "1.00", 1, 0);
        seed(OrderStatus.PENDING, "B", "2.00", 1, 1);
        seed(OrderStatus.CANCELLED, "C", "3.00", 1, 2);

        mvc.perform(authed(get("/api/orders")).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[1].status").value("PENDING"));
        mvc.perform(authed(get("/api/orders")).param("status", "DELIVERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void paginatesAndSortsByWhitelistedFields() throws Exception {
        seed(OrderStatus.PENDING, "Zulu", "30.00", 1, 0);
        seed(OrderStatus.PENDING, "Alpha", "10.00", 1, 1);
        seed(OrderStatus.PENDING, "Mike", "20.00", 1, 2);

        mvc.perform(authed(get("/api/orders")).param("sort", "total,asc").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].customerName").value("Alpha"))
                .andExpect(jsonPath("$.content[1].customerName").value("Mike"));
        mvc.perform(authed(get("/api/orders")).param("sort", "total,asc").param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].customerName").value("Zulu"));
        mvc.perform(authed(get("/api/orders")).param("sort", "customerName,desc"))
                .andExpect(jsonPath("$.content[0].customerName").value("Zulu"))
                .andExpect(jsonPath("$.content[2].customerName").value("Alpha"));
        mvc.perform(authed(get("/api/orders")).param("sort", "createdAt"))
                .andExpect(jsonPath("$.content[0].customerName").value("Zulu"));
        mvc.perform(authed(get("/api/orders")).param("sort", "status,desc"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "id", "createdBy", "items", "items.sku", "version", "createdAt;drop table orders",
            "createdAt,sideways", "createdAt,asc,extra", "total desc", "(select 1)", "TOTAL", ",asc", "created_at"})
    void sortOutsideTheWhitelistIs400(String sort) throws Exception {
        seed(OrderStatus.PENDING, "A", "1.00", 1, 0);
        mvc.perform(authed(get("/api/orders")).param("sort", sort))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("sort"))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("Exception"))));
        assertThat(orderCount()).isEqualTo(1); // y la tabla sigue ahi
    }

    @Test
    void rejectsAnyNonWhitelistedValueEvenWhenSortIsRepeated() throws Exception {
        mvc.perform(authed(get("/api/orders")).param("sort", "total,asc").param("sort", "password,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=101", "size=-1", "size=abc", "page=-1", "page=abc", "status=FOO",
            "status=pending"})
    void invalidPagingOrFilterIs400(String query) throws Exception {
        String[] kv = query.split("=");
        mvc.perform(authed(get("/api/orders")).param(kv[0], kv[1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void sizeUpTo100IsAccepted() throws Exception {
        mvc.perform(authed(get("/api/orders")).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    // ---- resumen ----------------------------------------------------------------------------------------------

    @Test
    void summaryCountsEveryStatusIncludingZeros() throws Exception {
        seed(OrderStatus.PENDING, "A", "1.00", 1, 0);
        seed(OrderStatus.PENDING, "B", "1.00", 1, 1);
        seed(OrderStatus.CONFIRMED, "C", "1.00", 1, 2);
        seed(OrderStatus.CANCELLED, "D", "1.00", 1, 3);

        mvc.perform(authed(get("/api/orders/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.byStatus.PENDING").value(2))
                .andExpect(jsonPath("$.byStatus.CONFIRMED").value(1))
                .andExpect(jsonPath("$.byStatus.SHIPPED").value(0))
                .andExpect(jsonPath("$.byStatus.DELIVERED").value(0))
                .andExpect(jsonPath("$.byStatus.CANCELLED").value(1));
    }

    @Test
    void summaryOfEmptyDatabaseIsAllZeros() throws Exception {
        mvc.perform(authed(get("/api/orders/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.byStatus.PENDING").value(0))
                .andExpect(jsonPath("$.byStatus.CANCELLED").value(0));
    }

    // ---- detalle ----------------------------------------------------------------------------------------------

    @Test
    void detailReturnsFullOrderWithItemsAndTwoDecimalMoney() throws Exception {
        UUID id = seed(OrderStatus.CONFIRMED, "Delta", "7.00", 2, 0);

        mvc.perform(authed(get("/api/orders/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.customerName").value("Delta"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-0"))
                .andExpect(jsonPath("$.items[1].sku").value("SKU-1"))
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.items[0].productId").exists())
                .andExpect(jsonPath("$.items[0].productName").value("Item 0"))
                .andExpect(content().string(containsString("\"total\":7.00")))
                .andExpect(content().string(containsString("\"unitPrice\":1.00")))
                .andExpect(content().string(containsString("\"lineTotal\":1.00")))
                .andExpect(jsonPath("$.notes").doesNotExist())
                .andExpect(content().string(containsString("\"notes\":null")))
                .andExpect(jsonPath("$.createdBy").value(USER_ID.toString()))
                .andExpect(jsonPath("$.createdByUsername").value(USERNAME))
                .andExpect(jsonPath("$.createdAt").value("2026-03-01T10:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").exists())
                // no se filtran campos internos
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void unknownOrderIs404InCommonFormat() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(authed(get("/api/orders/" + id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + id))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void malformedIdIs400() throws Exception {
        mvc.perform(authed(get("/api/orders/123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unsupportedMethodIs405WithValidationErrorCodeInCommonFormat() throws Exception {
        UUID id = seedOrder(OrderStatus.PENDING);
        mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/orders/" + id)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + id))
                .andExpect(jsonPath("$.message").value("Method Not Allowed"));
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void unknownRouteIs404NotFoundInCommonFormat() throws Exception {
        mvc.perform(authed(get("/api/nothing-here")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/nothing-here"));
    }
}
