package com.microinv.inventory.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microinv.inventory.notification.NotificationClient;
import com.microinv.inventory.product.Product;
import com.microinv.inventory.product.ProductRepository;
import com.microinv.inventory.stock.MovementType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base de las pruebas de integracion: contexto completo con H2 en modo PostgreSQL + Flyway, cliente de
 * notificaciones simulado (no hay red) y limpieza de tablas antes de cada prueba. Todas las clases
 * comparten la misma configuracion para reutilizar un unico contexto de Spring.
 */
@SpringBootTest(properties = {
        "app.security.jwt-secret=" + TestSecrets.JWT_SECRET,
        "app.security.jwt-issuer=" + TestSecrets.JWT_ISSUER,
        "app.security.internal-api-key=" + TestSecrets.INTERNAL_KEY})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @MockitoBean
    protected NotificationClient notificationClient;

    /** Espia sobre el repositorio real: permite comprobar en que orden se bloquean las filas. */
    @MockitoSpyBean
    protected ProductRepository productRepository;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from stock_movements");
        jdbc.update("delete from products");
    }

    protected UUID insertProduct(String sku, int stock, int lowStockThreshold) {
        Product p = Product.create(sku, "Producto " + sku, null, new BigDecimal("10.50"), stock, lowStockThreshold);
        productRepository.saveAndFlush(p);
        return p.getId();
    }

    protected int stockOf(UUID productId) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    protected boolean activeOf(UUID productId) {
        return jdbc.queryForObject("select active from products where id = ?", Boolean.class, productId);
    }

    protected int countMovements(UUID orderRef, MovementType type) {
        return jdbc.queryForObject("select count(*) from stock_movements where order_ref = ? and type = ?",
                Integer.class, orderRef, type.name());
    }

    protected int countAllMovements() {
        return jdbc.queryForObject("select count(*) from stock_movements", Integer.class);
    }

    protected JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    protected static String bearer() {
        return "Bearer " + JwtTestSupport.validToken();
    }
}
