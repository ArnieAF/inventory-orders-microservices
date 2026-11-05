package com.microinv.inventory.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * La V2 debe funcionar tambien sobre una BD que ya tenia datos de la V1 (despliegue previo a la cabecera):
 * migra a V1, inserta pedidos reservados/liberados, migra a V2 y comprueba las cabeceras generadas.
 * Usa su propia H2 en memoria (modo PostgreSQL), independiente de la del resto de pruebas.
 */
class MigrationV2BackfillTest {

    @Test
    void v2BackfillsHeadersFromExistingMovements() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:backfill;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("1").load().migrate();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where lower(table_name) = 'stock_reservations'", Integer.class)).isZero();

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        for (UUID p : List.of(p1, p2)) {
            jdbc.update("insert into products (id, sku, name, price, stock, created_at, updated_at) "
                    + "values (?, ?, 'n', 1.00, 10, current_timestamp, current_timestamp)", p, "SKU-" + p);
        }
        UUID reservedOnly = UUID.randomUUID();
        UUID reservedThenReleased = UUID.randomUUID();
        UUID twoLines = UUID.randomUUID();
        movement(jdbc, p1, -1, "RESERVE", reservedOnly);
        movement(jdbc, p1, -2, "RESERVE", reservedThenReleased);
        movement(jdbc, p1, 2, "RELEASE", reservedThenReleased);
        movement(jdbc, p1, -1, "RESERVE", twoLines);
        movement(jdbc, p2, -1, "RESERVE", twoLines);
        movement(jdbc, p1, 5, "ADJUSTMENT", null); // sin pedido: no genera cabecera

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        Map<UUID, String> headers = new java.util.HashMap<>();
        jdbc.query("select order_ref, status from stock_reservations",
                rs -> {
                    headers.put((UUID) rs.getObject(1), rs.getString(2));
                });
        assertThat(headers).containsOnlyKeys(reservedOnly, reservedThenReleased, twoLines);
        assertThat(headers.get(reservedOnly)).isEqualTo("RESERVED");
        assertThat(headers.get(reservedThenReleased)).isEqualTo("RELEASED");
        assertThat(headers.get(twoLines)).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("select count(*) from stock_reservations where created_at is null",
                Integer.class)).isZero();
    }

    private static void movement(JdbcTemplate jdbc, UUID product, int delta, String type, UUID orderRef) {
        jdbc.update("insert into stock_movements (id, product_id, delta, type, order_ref, created_at) "
                + "values (?, ?, ?, ?, ?, current_timestamp)", UUID.randomUUID(), product, delta, type, orderRef);
    }
}
