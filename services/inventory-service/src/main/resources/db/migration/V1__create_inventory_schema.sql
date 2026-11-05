-- Esquema inicial del inventory-service (contrato 6.2).
-- SQL portable: se ejecuta igual en PostgreSQL 16 y en H2 (modo PostgreSQL) para las pruebas.
-- Sin jsonb/citext/serial/gen_random_uuid(): los UUID los genera Java y el SKU se normaliza
-- a mayusculas en el codigo.

CREATE TABLE products (
    id                  UUID                     NOT NULL,
    sku                 VARCHAR(40)              NOT NULL,
    name                VARCHAR(120)             NOT NULL,
    description         VARCHAR(1000),
    price               NUMERIC(8, 2)            NOT NULL,
    stock               INTEGER                  NOT NULL,
    low_stock_threshold INTEGER                  NOT NULL DEFAULT 5,
    active              BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_stock_non_negative CHECK (stock >= 0),
    CONSTRAINT ck_products_price_non_negative CHECK (price >= 0),
    CONSTRAINT ck_products_threshold_non_negative CHECK (low_stock_threshold >= 0)
);

CREATE TABLE stock_movements (
    id         UUID                     NOT NULL,
    product_id UUID                     NOT NULL,
    delta      INTEGER                  NOT NULL,
    type       VARCHAR(20)              NOT NULL,
    reason     VARCHAR(200),
    order_ref  UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stock_movements PRIMARY KEY (id),
    CONSTRAINT fk_stock_movements_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_stock_movements_type CHECK (type IN ('RESERVE', 'RELEASE', 'ADJUSTMENT')),
    CONSTRAINT ck_stock_movements_delta_non_zero CHECK (delta <> 0),
    -- Garantiza la idempotencia de reserve/release por orderRef. Un UNIQUE con order_ref NULL
    -- no genera conflicto (NULL se considera distinto de NULL tanto en PostgreSQL como en H2),
    -- que es justo lo deseado para los ajustes manuales (sin pedido asociado).
    CONSTRAINT uq_stock_movements_order_product_type UNIQUE (order_ref, product_id, type)
);

-- PostgreSQL no indexa las claves foraneas automaticamente.
CREATE INDEX idx_stock_movements_product ON stock_movements (product_id);
