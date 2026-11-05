package com.microinv.inventory.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Comprueba las restricciones de la migracion V1 sobre H2 (modo PostgreSQL). El mismo script se verifico
 * ademas contra PostgreSQL 16 real (ver informe): la semantica de UNIQUE con NULL debe ser identica.
 */
class MigrationIntegrityTest extends AbstractIntegrationTest {

    private void insertProductRow(UUID id, String sku, int stock, String price) {
        jdbc.update("insert into products (id, sku, name, price, stock, created_at, updated_at) "
                + "values (?, ?, 'n', " + price + ", ?, current_timestamp, current_timestamp)", id, sku, stock);
    }

    private void insertMovement(UUID productId, int delta, String type, UUID orderRef) {
        jdbc.update("insert into stock_movements (id, product_id, delta, type, order_ref, created_at) "
                + "values (?, ?, ?, ?, ?, current_timestamp)", UUID.randomUUID(), productId, delta, type, orderRef);
    }

    @Test
    void stockCannotBeNegative() {
        assertThatThrownBy(() -> insertProductRow(UUID.randomUUID(), "NEG-STOCK", -1, "1.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void priceCannotBeNegative() {
        assertThatThrownBy(() -> insertProductRow(UUID.randomUUID(), "NEG-PRICE", 1, "-0.01"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void priceColumnEnforcesTheContractRange() {
        insertProductRow(UUID.randomUUID(), "MAX-PRICE", 1, "999999.99");
        assertThatThrownBy(() -> insertProductRow(UUID.randomUUID(), "OVER-PRICE", 1, "1000000.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void skuIsUnique() {
        insertProductRow(UUID.randomUUID(), "DUP", 1, "1.00");
        assertThatThrownBy(() -> insertProductRow(UUID.randomUUID(), "DUP", 1, "1.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void defaultsAreApplied() {
        UUID id = UUID.randomUUID();
        insertProductRow(id, "DEFAULTS", 3, "1.00");
        assertThat(jdbc.queryForObject("select low_stock_threshold from products where id = ?", Integer.class, id))
                .isEqualTo(5);
        assertThat(activeOf(id)).isTrue();
        assertThat(jdbc.queryForObject("select version from products where id = ?", Long.class, id)).isZero();
    }

    @Test
    void uniqueOrderProductTypeBlocksDuplicatesWhenOrderRefIsSet() {
        UUID product = UUID.randomUUID();
        insertProductRow(product, "IDEMP", 10, "1.00");
        UUID order = UUID.randomUUID();

        insertMovement(product, -1, "RESERVE", order);
        assertThatThrownBy(() -> insertMovement(product, -1, "RESERVE", order))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Mismo pedido y producto pero otro tipo, u otro pedido: permitido.
        insertMovement(product, 1, "RELEASE", order);
        insertMovement(product, -1, "RESERVE", UUID.randomUUID());
    }

    @Test
    void uniqueDoesNotBlockSeveralManualAdjustmentsWithNullOrderRef() {
        UUID product = UUID.randomUUID();
        insertProductRow(product, "ADJ", 10, "1.00");

        // Con order_ref NULL el UNIQUE no es una restriccion: debe permitir los duplicados (ajustes manuales).
        insertMovement(product, 5, "ADJUSTMENT", null);
        insertMovement(product, 5, "ADJUSTMENT", null);
        insertMovement(product, -2, "ADJUSTMENT", null);

        assertThat(jdbc.queryForObject("select count(*) from stock_movements where product_id = ?", Integer.class,
                product)).isEqualTo(3);
    }

    @Test
    void movementTypeAndDeltaAreConstrained() {
        UUID product = UUID.randomUUID();
        insertProductRow(product, "CHK", 10, "1.00");
        assertThatThrownBy(() -> insertMovement(product, 1, "BOGUS", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMovement(product, 0, "ADJUSTMENT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void movementMustReferenceAnExistingProduct() {
        assertThatThrownBy(() -> insertMovement(UUID.randomUUID(), 1, "ADJUSTMENT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
