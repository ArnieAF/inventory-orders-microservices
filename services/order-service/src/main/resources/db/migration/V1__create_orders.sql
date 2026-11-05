-- order-service V1: pedidos y su instantanea de items.
-- SQL portable (H2 modo PostgreSQL y PostgreSQL 16): sin jsonb, citext, serial ni gen_random_uuid().
-- Los UUID los genera la aplicacion.

CREATE TABLE orders (
    id                  UUID                     NOT NULL,
    customer_name       VARCHAR(120)             NOT NULL,
    notes               VARCHAR(500),
    status              VARCHAR(20)              NOT NULL,
    total               NUMERIC(14, 2)           NOT NULL,
    created_by          UUID                     NOT NULL,
    created_by_username VARCHAR(64)              NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_orders_total CHECK (total >= 0)
);

CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at);

CREATE TABLE order_items (
    id           UUID           NOT NULL,
    order_id     UUID           NOT NULL,
    product_id   UUID           NOT NULL,
    sku          VARCHAR(40)    NOT NULL,
    product_name VARCHAR(120)   NOT NULL,
    unit_price   NUMERIC(12, 2) NOT NULL,
    quantity     INTEGER        NOT NULL,
    line_total   NUMERIC(14, 2) NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    -- Sin productId repetido dentro de un pedido (el indice tambien sirve a la FK order_id).
    CONSTRAINT uq_order_items_order_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity >= 1 AND quantity <= 10000),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_order_items_line_total CHECK (line_total >= 0)
);
