package com.microinv.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.microinv.inventory.support.AbstractIntegrationTest;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ProductApiTest extends AbstractIntegrationTest {

    // ---------- helpers ----------

    private static String body(String sku, String name, String description, String price, String stock,
                               String threshold) {
        StringBuilder sb = new StringBuilder("{");
        if (sku != null) {
            sb.append("\"sku\":").append(sku).append(',');
        }
        if (name != null) {
            sb.append("\"name\":").append(name).append(',');
        }
        if (description != null) {
            sb.append("\"description\":").append(description).append(',');
        }
        if (price != null) {
            sb.append("\"price\":").append(price).append(',');
        }
        if (stock != null) {
            sb.append("\"stock\":").append(stock).append(',');
        }
        if (threshold != null) {
            sb.append("\"lowStockThreshold\":").append(threshold).append(',');
        }
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }
        return sb.append('}').toString();
    }

    private static String validBody(String sku) {
        return body("\"" + sku + "\"", "\"Teclado\"", "\"Mecanico\"", "10.5", "20", "3");
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", bearer());
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String content) {
        return authed(builder).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private UUID createViaApi(String sku, String name, String price, int stock, int threshold) throws Exception {
        MvcResult result = mvc.perform(json(post("/api/products"),
                        body("\"" + sku + "\"", "\"" + name + "\"", null, price, String.valueOf(stock),
                                String.valueOf(threshold))))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(result.getResponse().getContentAsString()).get("id").asText());
    }

    // ---------- crear ----------

    @Test
    void createReturns201WithNormalizedSkuAndContractFields() throws Exception {
        MvcResult result = mvc.perform(json(post("/api/products"),
                        body("\"abc-1.x_y\"", "\"  Teclado  \"", "\"Mecanico\"", "10.5", "20", "3")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/products/")))
                .andExpect(jsonPath("$.sku").value("ABC-1.X_Y"))
                .andExpect(jsonPath("$.name").value("Teclado"))
                .andExpect(jsonPath("$.description").value("Mecanico"))
                .andExpect(jsonPath("$.price").value(10.5))
                .andExpect(jsonPath("$.stock").value(20))
                .andExpect(jsonPath("$.lowStockThreshold").value(3))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andReturn();
        // El dinero se serializa como decimal con 2 decimales, nunca como double ni notacion cientifica.
        assertThat(result.getResponse().getContentAsString()).contains("\"price\":10.50");
    }

    @Test
    void createAppliesDefaultThresholdAndNullDescription() throws Exception {
        mvc.perform(json(post("/api/products"), body("\"D-1\"", "\"X\"", null, "0", "0", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lowStockThreshold").value(5))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.price").value(0));
    }

    @Test
    void createAcceptsExactBoundaries() throws Exception {
        String sku40 = "A".repeat(40);
        String name120 = "n".repeat(120);
        String desc1000 = "d".repeat(1000);
        mvc.perform(json(post("/api/products"), body("\"" + sku40 + "\"", "\"" + name120 + "\"",
                        "\"" + desc1000 + "\"", "999999.99", "0", "0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(999999.99))
                .andExpect(jsonPath("$.stock").value(0))
                .andExpect(jsonPath("$.lowStockThreshold").value(0));
    }

    static Stream<Arguments> invalidCreateBodies() {
        String q = "\"";
        return Stream.of(
                Arguments.of("sku ausente", body(null, q + "N" + q, null, "1", "1", null), "sku"),
                Arguments.of("sku vacio", body(q + q, q + "N" + q, null, "1", "1", null), "sku"),
                Arguments.of("sku con espacio", body(q + "A B" + q, q + "N" + q, null, "1", "1", null), "sku"),
                Arguments.of("sku con caracter invalido", body(q + "A/B" + q, q + "N" + q, null, "1", "1", null),
                        "sku"),
                Arguments.of("sku de 41", body(q + "A".repeat(41) + q, q + "N" + q, null, "1", "1", null), "sku"),
                Arguments.of("nombre ausente", body(q + "S" + q, null, null, "1", "1", null), "name"),
                Arguments.of("nombre en blanco", body(q + "S" + q, q + "   " + q, null, "1", "1", null), "name"),
                Arguments.of("nombre de 121", body(q + "S" + q, q + "n".repeat(121) + q, null, "1", "1", null),
                        "name"),
                Arguments.of("descripcion de 1001",
                        body(q + "S" + q, q + "N" + q, q + "d".repeat(1001) + q, "1", "1", null), "description"),
                Arguments.of("precio ausente", body(q + "S" + q, q + "N" + q, null, null, "1", null), "price"),
                Arguments.of("precio negativo", body(q + "S" + q, q + "N" + q, null, "-0.01", "1", null), "price"),
                Arguments.of("precio 1000000", body(q + "S" + q, q + "N" + q, null, "1000000", "1", null), "price"),
                Arguments.of("precio con 3 decimales", body(q + "S" + q, q + "N" + q, null, "1.234", "1", null),
                        "price"),
                Arguments.of("stock ausente", body(q + "S" + q, q + "N" + q, null, "1", null, null), "stock"),
                Arguments.of("stock negativo", body(q + "S" + q, q + "N" + q, null, "1", "-1", null), "stock"),
                Arguments.of("stock > 1e9", body(q + "S" + q, q + "N" + q, null, "1", "1000000001", null), "stock"),
                Arguments.of("umbral negativo", body(q + "S" + q, q + "N" + q, null, "1", "1", "-1"),
                        "lowStockThreshold"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreateBodies")
    void createRejectsInvalidInput(String label, String requestBody, String expectedField) throws Exception {
        mvc.perform(json(post("/api/products"), requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/products"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.details[*].field").value(hasItem(expectedField)));
        assertThat(jdbc.queryForObject("select count(*) from products", Integer.class)).isZero();
    }

    @Test
    void malformedJsonReturnsGenericValidationErrorWithoutInternals() throws Exception {
        mvc.perform(json(post("/api/products"), "{\"sku\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("com.fasterxml"))));
        mvc.perform(json(post("/api/products"), "{\"sku\":\"A\",\"name\":\"N\",\"price\":\"abc\",\"stock\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void integerFieldsRejectFractionsInsteadOfTruncatingThem() throws Exception {
        mvc.perform(json(post("/api/products"), body("\"FRAC-1\"", "\"N\"", null, "1", "1.5", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        UUID id = insertProduct("FRAC-2", 5, 1);
        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"), "{\"delta\":1.5,\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        assertThat(stockOf(id)).isEqualTo(5);
    }

    @Test
    void duplicateSkuReturns409EvenWithDifferentCase() throws Exception {
        mvc.perform(json(post("/api/products"), validBody("dup-1"))).andExpect(status().isCreated());
        mvc.perform(json(post("/api/products"), validBody("DUP-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.status").value(409));
        assertThat(jdbc.queryForObject("select count(*) from products", Integer.class)).isEqualTo(1);
    }

    // ---------- leer ----------

    @Test
    void getByIdAndNotFoundAndInvalidId() throws Exception {
        UUID id = createViaApi("G-1", "Uno", "5.00", 4, 1);
        mvc.perform(authed(get("/api/products/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.sku").value("G-1"));
        mvc.perform(authed(get("/api/products/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(authed(get("/api/products/not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---------- actualizar ----------

    @Test
    void updateChangesDetailsButNeverTheStock() throws Exception {
        UUID id = createViaApi("U-1", "Antes", "5.00", 9, 2);
        MvcResult result = mvc.perform(json(put("/api/products/" + id),
                        "{\"sku\":\"u-2\",\"name\":\"Despues\",\"description\":\"nueva\",\"price\":7.25,"
                                + "\"lowStockThreshold\":4,\"stock\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("U-2"))
                .andExpect(jsonPath("$.name").value("Despues"))
                .andExpect(jsonPath("$.description").value("nueva"))
                .andExpect(jsonPath("$.price").value(7.25))
                .andExpect(jsonPath("$.lowStockThreshold").value(4))
                .andExpect(jsonPath("$.stock").value(9))
                .andReturn();
        assertThat(stockOf(id)).isEqualTo(9);
        JsonNode updated = json(result.getResponse().getContentAsString());
        assertThat(updated.get("updatedAt").asText()).isNotBlank();
    }

    @Test
    void updateKeepingOwnSkuIsAllowedButTakingAnotherSkuIs409() throws Exception {
        UUID first = createViaApi("P-1", "Uno", "1.00", 1, 1);
        createViaApi("P-2", "Dos", "1.00", 1, 1);
        String sameSku = "{\"sku\":\"p-1\",\"name\":\"Uno bis\",\"price\":1,\"lowStockThreshold\":1}";
        mvc.perform(json(put("/api/products/" + first), sameSku)).andExpect(status().isOk());
        String takenSku = "{\"sku\":\"P-2\",\"name\":\"Uno\",\"price\":1,\"lowStockThreshold\":1}";
        mvc.perform(json(put("/api/products/" + first), takenSku))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"));
    }

    @Test
    void updateValidatesInputAndUnknownIdIs404() throws Exception {
        UUID id = createViaApi("V-1", "Uno", "1.00", 1, 1);
        mvc.perform(json(put("/api/products/" + id),
                        "{\"sku\":\"V 1\",\"name\":\"\",\"price\":-1,\"lowStockThreshold\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field").value(hasItem("sku")))
                .andExpect(jsonPath("$.details[*].field").value(hasItem("name")))
                .andExpect(jsonPath("$.details[*].field").value(hasItem("price")))
                .andExpect(jsonPath("$.details[*].field").value(hasItem("lowStockThreshold")));
        mvc.perform(json(put("/api/products/" + UUID.randomUUID()),
                        "{\"sku\":\"V-9\",\"name\":\"X\",\"price\":1,\"lowStockThreshold\":1}"))
                .andExpect(status().isNotFound());
    }

    // ---------- baja logica ----------

    @Test
    void deleteIsLogicalAndHidesTheProduct() throws Exception {
        UUID id = createViaApi("DEL-1", "Borrable", "1.00", 3, 1);
        createViaApi("KEEP-1", "Se queda", "1.00", 3, 1);

        mvc.perform(authed(delete("/api/products/" + id))).andExpect(status().isNoContent());

        assertThat(activeOf(id)).isFalse(); // la fila sigue existiendo
        mvc.perform(authed(get("/api/products/" + id))).andExpect(status().isNotFound());
        mvc.perform(authed(get("/api/products")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[*].sku").value(not(hasItem("DEL-1"))));
        mvc.perform(authed(get("/api/products/summary")))
                .andExpect(jsonPath("$.totalProducts").value(1))
                .andExpect(jsonPath("$.totalUnits").value(3));
        // Un producto dado de baja no se puede editar, ajustar ni borrar de nuevo.
        mvc.perform(json(put("/api/products/" + id),
                        "{\"sku\":\"DEL-1\",\"name\":\"x\",\"price\":1,\"lowStockThreshold\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"), "{\"delta\":1,\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(authed(delete("/api/products/" + id))).andExpect(status().isNotFound());
        // Su SKU sigue ocupado (la restriccion UNIQUE de la BD es global).
        mvc.perform(json(post("/api/products"), validBody("DEL-1"))).andExpect(status().isConflict());
    }

    // ---------- ajustes de stock ----------

    @Test
    void adjustmentsAddAndSubtractAndRecordMovements() throws Exception {
        UUID id = createViaApi("ADJ-1", "Ajustable", "1.00", 10, 2);

        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"),
                        "{\"delta\":5,\"reason\":\"Compra\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(15));
        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"),
                        "{\"delta\":-15,\"reason\":\"Inventario fisico\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(0));

        assertThat(stockOf(id)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from stock_movements where product_id = ? and type = 'ADJUSTMENT' "
                        + "and order_ref is null", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void adjustmentThatWouldGoNegativeIs409AndChangesNothing() throws Exception {
        UUID id = createViaApi("ADJ-2", "Ajustable", "1.00", 3, 1);
        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"),
                        "{\"delta\":-4,\"reason\":\"Merma\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details[0].productId").value(id.toString()))
                .andExpect(jsonPath("$.details[0].requested").value(4))
                .andExpect(jsonPath("$.details[0].available").value(3));
        assertThat(stockOf(id)).isEqualTo(3);
        assertThat(countAllMovements()).isZero();
    }

    @Test
    void stockUpToOneBillionIsAcceptedAndBeyondIsRejected() throws Exception {
        mvc.perform(json(post("/api/products"), body("\"BIG-OK\"", "\"N\"", null, "1", "1000000000", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stock").value(1_000_000_000));
        mvc.perform(json(post("/api/products"), body("\"BIG-KO\"", "\"N\"", null, "1", "1000000001", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field").value(hasItem("stock")));
        mvc.perform(json(post("/api/products"), body("\"BIG-INT\"", "\"N\"", null, "1", "2147483647", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adjustmentThatWouldExceedOneBillionUnitsIs400() throws Exception {
        UUID id = createViaApi("ADJ-3", "Grande", "1.00", 1_000_000_000, 1);
        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"), "{\"delta\":1,\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"),
                        "{\"delta\":1000000000,\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(stockOf(id)).isEqualTo(1_000_000_000);
    }

    @Test
    void deltaLimitsAreEnforcedAndNeverOverflow() throws Exception {
        UUID id = createViaApi("ADJ-4", "Limites", "1.00", 5, 1);
        // Integer.MIN_VALUE: -delta desbordaria y "requested" saldria negativo; ahora es 400 sin tocar nada.
        for (String delta : new String[] {"-2147483648", "2147483647", "-1000000001", "1000000001"}) {
            mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"),
                            "{\"delta\":" + delta + ",\"reason\":\"x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[*].field").value(hasItem("delta")));
        }
        assertThat(stockOf(id)).isEqualTo(5);
        assertThat(countAllMovements()).isZero();

        // En el borde (|delta| = 1e9) es valido: negativo, devuelve INSUFFICIENT_STOCK con "requested" positivo.
        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"),
                        "{\"delta\":-1000000000,\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details[0].requested").value(1_000_000_000))
                .andExpect(jsonPath("$.details[0].available").value(5));
        // y positivo hasta el tope del stock
        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"),
                        "{\"delta\":999999995,\"reason\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(1_000_000_000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAdjustments")
    void adjustmentValidatesInput(String label, String requestBody, String field) throws Exception {
        UUID id = createViaApi("ADJ-V", "Ajustable", "1.00", 3, 1);
        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"), requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field").value(hasItem(field)));
        assertThat(stockOf(id)).isEqualTo(3);
    }

    static Stream<Arguments> invalidAdjustments() {
        return Stream.of(
                Arguments.of("delta cero", "{\"delta\":0,\"reason\":\"x\"}", "delta"),
                Arguments.of("delta ausente", "{\"reason\":\"x\"}", "delta"),
                Arguments.of("motivo ausente", "{\"delta\":1}", "reason"),
                Arguments.of("motivo en blanco", "{\"delta\":1,\"reason\":\"  \"}", "reason"),
                Arguments.of("motivo de 201", "{\"delta\":1,\"reason\":\"" + "r".repeat(201) + "\"}", "reason"));
    }

    @Test
    void adjustmentReasonAcceptsExactly200Characters() throws Exception {
        UUID id = createViaApi("ADJ-200", "Ajustable", "1.00", 3, 1);
        mvc.perform(json(post("/api/products/" + id + "/stock-adjustments"),
                        "{\"delta\":1,\"reason\":\"" + "r".repeat(200) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void adjustmentOnUnknownProductIs404() throws Exception {
        mvc.perform(json(post("/api/products/" + UUID.randomUUID() + "/stock-adjustments"),
                        "{\"delta\":1,\"reason\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---------- listado ----------

    @Test
    void listSearchesBySkuOrNameCaseInsensitivelyAndTreatsWildcardsLiterally() throws Exception {
        createViaApi("KB-100", "Teclado mecanico", "1.00", 1, 1);
        createViaApi("MS-200", "Raton optico", "1.00", 1, 1);
        createViaApi("OFF-50", "Oferta 50% descuento", "1.00", 1, 1);

        mvc.perform(authed(get("/api/products").param("search", "teclado")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].sku").value("KB-100"));
        mvc.perform(authed(get("/api/products").param("search", "ms-2")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("Raton optico"));
        mvc.perform(authed(get("/api/products").param("search", "50%")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].sku").value("OFF-50"));
        mvc.perform(authed(get("/api/products").param("search", "%")))
                .andExpect(jsonPath("$.content", hasSize(1))); // "%" literal: solo la oferta lo contiene
        mvc.perform(authed(get("/api/products").param("search", "_")))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(authed(get("/api/products").param("search", "x".repeat(101))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listFiltersLowStock() throws Exception {
        createViaApi("L-LOW", "Bajo", "1.00", 2, 5);
        createViaApi("L-EDGE", "En el umbral", "1.00", 5, 5);
        createViaApi("L-OK", "Normal", "1.00", 6, 5);
        createViaApi("L-ZERO", "Agotado", "1.00", 0, 0);

        mvc.perform(authed(get("/api/products").param("lowStock", "true").param("sort", "sku,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].sku").value(org.hamcrest.Matchers.contains("L-EDGE", "L-LOW",
                        "L-ZERO")));
        mvc.perform(authed(get("/api/products").param("lowStock", "false")))
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void listPaginatesWithContractShape() throws Exception {
        for (int i = 1; i <= 5; i++) {
            createViaApi("PG-" + i, "Producto " + i, "1.00", i, 1);
        }
        mvc.perform(authed(get("/api/products").param("page", "1").param("size", "2").param("sort", "sku,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[*].sku").value(org.hamcrest.Matchers.contains("PG-3", "PG-4")));
        mvc.perform(authed(get("/api/products").param("size", "100"))).andExpect(status().isOk());
    }

    @Test
    void listRejectsInvalidPagingParameters() throws Exception {
        mvc.perform(authed(get("/api/products").param("size", "101")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(authed(get("/api/products").param("size", "0"))).andExpect(status().isBadRequest());
        mvc.perform(authed(get("/api/products").param("page", "-1"))).andExpect(status().isBadRequest());
        mvc.perform(authed(get("/api/products").param("page", "abc"))).andExpect(status().isBadRequest());
        mvc.perform(authed(get("/api/products").param("lowStock", "maybe"))).andExpect(status().isBadRequest());
    }

    @Test
    void listSortsByWhitelistedFields() throws Exception {
        createViaApi("S-1", "Charlie", "30.00", 3, 1);
        createViaApi("S-2", "Alpha", "10.00", 9, 1);
        createViaApi("S-3", "Bravo", "20.00", 6, 1);

        assertSkus("sort=name,asc", "S-2", "S-3", "S-1");
        assertSkus("sort=price,desc", "S-1", "S-3", "S-2");
        assertSkus("sort=stock,asc", "S-1", "S-3", "S-2");
        assertSkus("sort=sku,desc", "S-3", "S-2", "S-1");
        assertSkus("sort=createdAt,asc", "S-1", "S-2", "S-3");
        assertSkus("sort=price", "S-2", "S-3", "S-1"); // sin direccion: ascendente
    }

    private void assertSkus(String query, String... expected) throws Exception {
        mvc.perform(authed(get("/api/products?" + query)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku").value(org.hamcrest.Matchers.contains((Object[]) expected)));
    }

    @Test
    void listRejectsSortOutsideWhitelistWith400() throws Exception {
        createViaApi("W-1", "Uno", "1.00", 1, 1);
        for (String bad : new String[] {"sort=password,asc", "sort=id,asc", "sort=version", "sort=description",
                "sort=name,sideways", "sort=name;drop%20table%20products", "sort=Name", "sort=active,desc"}) {
            mvc.perform(authed(get("/api/products?" + bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[0].field").value("sort"));
        }
    }

    // ---------- resumen ----------

    @Test
    void summaryCountsOnlyActiveProducts() throws Exception {
        createViaApi("SUM-1", "Uno", "1.00", 2, 5);   // bajo
        createViaApi("SUM-2", "Dos", "1.00", 10, 5);  // normal
        UUID gone = createViaApi("SUM-3", "Tres", "1.00", 1, 5); // bajo pero dado de baja
        mvc.perform(authed(delete("/api/products/" + gone))).andExpect(status().isNoContent());

        mvc.perform(authed(get("/api/products/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProducts").value(2))
                .andExpect(jsonPath("$.lowStockCount").value(1))
                .andExpect(jsonPath("$.totalUnits").value(12));
    }

    @Test
    void summaryOfEmptyInventoryIsZero() throws Exception {
        mvc.perform(authed(get("/api/products/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProducts").value(0))
                .andExpect(jsonPath("$.lowStockCount").value(0))
                .andExpect(jsonPath("$.totalUnits").value(0));
    }

    @Test
    void unknownRoutesUseTheCommonErrorFormat() throws Exception {
        mvc.perform(authed(get("/api/nothing")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/nothing"));
        mvc.perform(authed(delete("/api/products")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
