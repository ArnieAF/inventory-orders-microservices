package com.microinv.orders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.orders.client.InventoryClient;
import com.microinv.orders.client.NotificationClient;
import com.microinv.orders.client.ReserveItem;
import com.microinv.orders.client.ReserveResult;
import com.microinv.orders.client.ReservedItem;
import com.microinv.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base de las pruebas de API: contexto completo (seguridad real, Flyway real, H2 en modo PostgreSQL) con los
 * clientes de inventario y notificaciones simulados. Todas las subclases comparten un unico contexto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AbstractApiTest {

    /** Usuario autenticado por defecto en las pruebas. */
    static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final String USERNAME = "alice";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @MockitoBean
    InventoryClient inventory;
    @MockitoBean
    NotificationClient notifications;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from order_items");
        jdbc.update("delete from orders");
    }

    // ---- peticiones -------------------------------------------------------------------------------------------

    static String bearer(String token) {
        return "Bearer " + token;
    }

    static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
        return authed(request, USER_ID, USERNAME);
    }

    static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, UUID userId, String username) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.valid(userId, username)));
    }

    // ---- inventario simulado ----------------------------------------------------------------------------------

    /** Producto del catalogo simulado de inventario. */
    record Product(UUID id, String sku, String name, BigDecimal price) {
        static Product of(String sku, String name, String price) {
            return new Product(UUID.randomUUID(), sku, name, new BigDecimal(price));
        }
    }

    /**
     * Inventario simulado: {@code reserve} devuelve la instantanea (sku, nombre y precio) de cada producto pedido.
     * El precio sale SIEMPRE de aqui.
     */
    void stubReserveWith(Product... catalog) {
        Map<UUID, Product> byId = new HashMap<>();
        for (Product product : catalog) {
            byId.put(product.id(), product);
        }
        when(inventory.reserve(any(UUID.class), anyList())).thenAnswer(invocation -> {
            List<ReserveItem> requested = invocation.getArgument(1);
            List<ReservedItem> items = new ArrayList<>();
            for (ReserveItem item : requested) {
                Product p = byId.get(item.productId());
                items.add(new ReservedItem(p.id(), p.sku(), p.name(), p.price(), item.quantity()));
            }
            return new ReserveResult(items);
        });
    }

    // ---- datos directos en BD (independientes de la API de entidades) -----------------------------------------

    UUID seedOrder(OrderStatus status) {
        return seedOrder(status, UUID.randomUUID(), "bob", "ACME", new BigDecimal("10.00"), 1, Instant.now());
    }

    UUID seedOrder(OrderStatus status, UUID createdBy, String username, String customer, BigDecimal total,
                   int itemCount, Instant createdAt) {
        UUID id = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbc.update("insert into orders (id, customer_name, notes, status, total, created_by, created_by_username,"
                        + " created_at, updated_at, version) values (?, ?, null, ?, ?, ?, ?, ?, ?, 0)",
                id, customer, status.name(), total, createdBy, username, at, at);
        for (int i = 0; i < itemCount; i++) {
            jdbc.update("insert into order_items (id, order_id, product_id, sku, product_name, unit_price, quantity,"
                            + " line_total) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), id, UUID.randomUUID(), "SKU-" + i, "Item " + i, new BigDecimal("1.00"), 1,
                    new BigDecimal("1.00"));
        }
        return id;
    }

    long orderCount() {
        Long count = jdbc.queryForObject("select count(*) from orders", Long.class);
        return count == null ? 0 : count;
    }

    String statusInDb(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    long versionInDb(UUID orderId) {
        Long version = jdbc.queryForObject("select version from orders where id = ?", Long.class, orderId);
        return version == null ? -1 : version;
    }
}
