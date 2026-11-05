package com.microinv.inventory.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Comprueba las restricciones de las migraciones V1 y V2 sobre H2 (modo PostgreSQL). ESTE TEST NO EJECUTA
 * PostgreSQL: la comprobacion contra PostgreSQL 16 real (en especial que un UNIQUE con order_ref NULL permita
 * duplicados igual que en H2) se hizo a mano, fuera de la suite, con un contenedor efimero sin puertos:
 * <pre>
 * docker run --rm -v "&lt;ruta&gt;/src/main/resources/db/migration:/mig:ro" -v "&lt;ruta-script&gt;:/t:ro" postgres:16-alpine \
 *   sh -c 'su-exec postgres initdb -D /tmp/pgdata -A trust &amp;&amp; \
 *          su-exec postgres pg_ctl -D /tmp/pgdata -o "-c listen_addresses= -k /tmp" -w start &amp;&amp; \
 *          psql -X -h /tmp -U postgres -f /t/verify.sql'
 * </pre>
 * (el script aplica V1 y V2, comprueba CHECK/UNIQUE/FK y la clave primaria de la cabecera). Si cambian las
 * migraciones hay que repetir esa comprobacion manual.
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

    private void insertReservationRow(UUID orderRef, String status) {
        jdbc.update("insert into stock_reservations (order_ref, status, created_at) "
                + "values (?, ?, current_timestamp)", orderRef, status);
    }

    @Test
    void reservationHeaderHasPrimaryKeyOnOrderRef() {
        UUID order = UUID.randomUUID();
        insertReservationRow(order, "RESERVED");
        assertThatThrownBy(() -> insertReservationRow(order, "RELEASED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertReservationRow(UUID.randomUUID(), "RELEASED");
    }

    @Test
    void reservationStatusIsConstrainedToReservedOrReleased() {
        assertThatThrownBy(() -> insertReservationRow(UUID.randomUUID(), "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReservationRow(UUID.randomUUID(), "reserved"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void movementMustReferenceAnExistingProduct() {
        assertThatThrownBy(() -> insertMovement(UUID.randomUUID(), 1, "ADJUSTMENT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
