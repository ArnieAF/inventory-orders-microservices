package com.microinv.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** La migracion V1 (Flyway real sobre H2 en modo PostgreSQL) crea el esquema del contrato con sus restricciones. */
class MigrationSchemaTest extends AbstractApiTest {

    private List<String> columns(String table) {
        return jdbc.queryForList(
                "select lower(column_name) from information_schema.columns where lower(table_name) = ? "
                        + "order by ordinal_position", String.class, table);
    }

    @Test
    void ordersTableHasTheContractColumns() {
        assertThat(columns("orders")).containsExactly("id", "customer_name", "notes", "status", "total", "created_by",
                "created_by_username", "created_at", "updated_at", "version");
    }

    @Test
    void orderItemsTableHasTheContractColumns() {
        assertThat(columns("order_items")).containsExactly("id", "order_id", "product_id", "sku", "product_name",
                "unit_price", "quantity", "line_total");
    }

    @Test
    void flywayAppliedV1() {
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void databaseRejectsAnUnknownStatus() {
        UUID id = seedOrder(OrderStatus.PENDING);
        assertThatThrownBy(() -> jdbc.update("update orders set status = 'LOST' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNegativeTotalsAndPrices() {
        UUID id = seedOrder(OrderStatus.PENDING);
        assertThatThrownBy(() -> jdbc.update("update orders set total = -1 where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update order_items set unit_price = -1 where order_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsAProductRepeatedWithinAnOrderAndOutOfRangeQuantities() {
        UUID id = seedOrder(OrderStatus.PENDING);
        UUID product = UUID.randomUUID();
        jdbc.update("insert into order_items (id, order_id, product_id, sku, product_name, unit_price, quantity,"
                + " line_total) values (?, ?, ?, 'S', 'N', 1.00, 1, 1.00)", UUID.randomUUID(), id, product);

        assertThatThrownBy(() -> jdbc.update("insert into order_items (id, order_id, product_id, sku, product_name,"
                + " unit_price, quantity, line_total) values (?, ?, ?, 'S', 'N', 1.00, 1, 1.00)",
                UUID.randomUUID(), id, product)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into order_items (id, order_id, product_id, sku, product_name,"
                + " unit_price, quantity, line_total) values (?, ?, ?, 'S', 'N', 1.00, 0, 0.00)",
                UUID.randomUUID(), id, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into order_items (id, order_id, product_id, sku, product_name,"
                + " unit_price, quantity, line_total) values (?, ?, ?, 'S', 'N', 1.00, 10001, 1.00)",
                UUID.randomUUID(), id, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void itemsMustReferenceAnExistingOrderAndAreDeletedWithIt() {
        assertThatThrownBy(() -> jdbc.update("insert into order_items (id, order_id, product_id, sku, product_name,"
                + " unit_price, quantity, line_total) values (?, ?, ?, 'S', 'N', 1.00, 1, 1.00)",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID id = seedOrder(OrderStatus.PENDING, UUID.randomUUID(), "bob", "ACME", new BigDecimal("2.00"), 2,
                java.time.Instant.now());
        jdbc.update("delete from orders where id = ?", id);
        assertThat(jdbc.queryForObject("select count(*) from order_items where order_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    void moneyColumnsKeepExactlyTwoDecimals() {
        UUID id = seedOrder(OrderStatus.PENDING, UUID.randomUUID(), "bob", "ACME", new BigDecimal("12.3"), 1,
                java.time.Instant.now());
        BigDecimal total = jdbc.queryForObject("select total from orders where id = ?", BigDecimal.class, id);
        assertThat(total.scale()).isEqualTo(2);
        assertThat(total).isEqualByComparingTo("12.30");
    }
}
