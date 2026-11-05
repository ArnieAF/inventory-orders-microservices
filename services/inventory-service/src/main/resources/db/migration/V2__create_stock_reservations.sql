-- Cabecera por orderRef (contrato 6.2, "Cabecera por orderRef").
-- La PK serializa las peticiones del mismo orderRef aunque toquen productos distintos, y la lapida
-- RELEASED (un release que llega antes que la reserva) impide que una reserva tardia descuente stock
-- para un pedido ya compensado. SQL portable (H2 modo PostgreSQL y PostgreSQL 16).

CREATE TABLE stock_reservations (
    order_ref  UUID                     NOT NULL,
    status     VARCHAR(20)              NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stock_reservations PRIMARY KEY (order_ref),
    CONSTRAINT ck_stock_reservations_status CHECK (status IN ('RESERVED', 'RELEASED'))
);

-- Datos creados con la V1 (antes de existir la cabecera): un pedido con movimientos RESERVE esta
-- RESERVED, o RELEASED si ya tiene algun movimiento RELEASE.
INSERT INTO stock_reservations (order_ref, status, created_at)
SELECT m.order_ref,
       CASE WHEN EXISTS (SELECT 1 FROM stock_movements r
                          WHERE r.order_ref = m.order_ref AND r.type = 'RELEASE')
            THEN 'RELEASED' ELSE 'RESERVED' END,
       MIN(m.created_at)
  FROM stock_movements m
 WHERE m.order_ref IS NOT NULL AND m.type = 'RESERVE'
 GROUP BY m.order_ref;
