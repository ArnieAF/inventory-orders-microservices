# Contrato técnico — Sistema de Inventario y Pedidos

Fuente de verdad para todos los agentes. Si algo aquí es ambiguo o no alcanza, **repórtalo al orquestador; no lo inventes ni cambies el contrato por tu cuenta.**

## 1. Arquitectura

```
Navegador ──► frontend (nginx :3000) ──► api-gateway :8080 ──► auth-service :8081         ──► auth-db
                 │  /api/*  → gateway                      ├──► inventory-service :8082    ──► inventory-db
                 │  /ws     → gateway (WebSocket)          ├──► order-service :8083        ──► order-db
                                                           └──► notification-service :8084 ──► notification-db
order-service ──REST /internal/*──► inventory-service
inventory-service / order-service ──REST /internal/*──► notification-service ──WebSocket(STOMP)──► navegador
```

- Solo `frontend` (3000) y `api-gateway` (8080) publican puertos al host. Servicios y bases de datos solo viven en la red interna de Docker Compose.
- Un PostgreSQL 16 **por servicio**. Nombres DNS internos: `auth-db`, `inventory-db`, `order-db`, `notification-db` (puerto 5432). Base y usuario: `auth`, `inventory`, `orders`, `notifications`.
- Sin Eureka/Config Server/broker de mensajes (fuera del MVP). Descubrimiento = DNS de Compose.
- Cada servicio es un proyecto Maven **independiente** (sin POM padre común ni librería compartida) en `services/<nombre>/`.

## 2. Stack

| Pieza | Versión |
|---|---|
| Java | 17 |
| Spring Boot | 3.5.x (último parche; verifica compatibilidad con la versión de Spring Cloud) |
| Spring Cloud (solo gateway) | tren `2025.0.x`, Spring Cloud Gateway **reactivo** (WebFlux) |
| Migraciones | Flyway |
| BD | PostgreSQL 16 (`postgres:16-alpine`) |
| Paquete base | `com.microinv.<servicio>` (`auth`, `gateway`, `inventory`, `orders`, `notifications`) |
| Frontend | React + TypeScript (strict) + Tailwind CSS + Vite, React Router, TanStack Query, `@stomp/stompjs`, Vitest |
| Imágenes | build `maven:3.9-eclipse-temurin-17` → runtime `eclipse-temurin:17-jre-alpine`, usuario no root |

### Reglas de portabilidad (para poder probar sin Postgres real)
- Las migraciones Flyway usan SQL **portable**: nada de `jsonb`, `citext`, `gen_random_uuid()`, `serial`. UUID generado en Java. Textos "únicos sin mayúsculas" se normalizan en código (guardar en minúsculas).
- Los tests corren con **H2 en modo PostgreSQL** + Flyway, **sin Docker socket ni Testcontainers**, dentro de `maven:3.9-eclipse-temurin-17`.
- Comando de verificación por servicio (desde la raíz del worktree):
  `docker run --rm -v "<ruta-abs-servicio>:/app" -v micro-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-17 mvn -B -q test`
- Postgres real se verifica después en el conjunto (docker compose), lo hace el orquestador.

## 3. Seguridad (decisiones cerradas)

**JWT**
- HS256 con `JWT_SECRET` (≥ 32 caracteres, del entorno; jamás en el repo). Claims: `iss`=`micro-inventory`, `sub`=UUID del usuario, `username`, `iat`, `exp` (por defecto 60 min, `JWT_EXPIRATION_MINUTES`). Sin refresh token (fuera del MVP).
- El **gateway** valida el JWT antes de enrutar (rechaza temprano) y **cada servicio vuelve a validarlo** (Spring Security resource server, defensa en profundidad). El usuario actual sale de `sub`/`username` del token, nunca de headers del cliente.
- Rutas públicas en el gateway: `POST /api/auth/register`, `POST /api/auth/login`, `GET /actuator/health`, `/ws/**` (el handshake de navegador no puede llevar `Authorization`; la autenticación se hace en el frame STOMP `CONNECT`, ver §7).
- El gateway **elimina** de las peticiones entrantes los headers `X-Internal-Key`, `X-User-Id`, `X-Username` antes de reenviar.
- El gateway **solo enruta** los prefijos de §4. `/internal/**` nunca se enruta (404).

**Llamadas servicio→servicio**
- Rutas `/internal/**`, header `X-Internal-Key: <INTERNAL_API_KEY>` (comparación en tiempo constante). Esas rutas **no** aceptan JWT y no existen para el navegador.
- Fallo del servicio destino ⇒ el llamante responde 503 `SERVICE_UNAVAILABLE` (salvo notificaciones, que son *best-effort*: se registra y se ignora, jamás rompe la operación principal). Timeouts: conexión 2 s, lectura 5 s.

**Roles:** un solo rol (decisión del usuario). Todo usuario autenticado puede gestionar productos, pedidos y ver todo. Es una herramienta interna de back-office. El registro abierto se controla con `REGISTRATION_ENABLED` (por defecto `true`).

**Otros**
- Contraseñas con BCrypt. Login con error genérico (`401 INVALID_CREDENTIALS`) sin revelar si el usuario existe.
- Errores al cliente sin stack traces, SQL ni nombres de clases.
- Actuator: solo `health` expuesto, sin detalles.
- Entradas validadas (Bean Validation) con límites de longitud; listados con `size` máximo 100.

## 4. Rutas públicas (vía gateway, sin reescritura de path)

| Prefijo | Servicio destino |
|---|---|
| `/api/auth/**` | auth-service |
| `/api/products/**` | inventory-service |
| `/api/orders/**` | order-service |
| `/api/notifications/**` | notification-service |
| `/ws/**` | notification-service (WebSocket) |

## 5. Formato común

**Error** (todas las respuestas 4xx/5xx de todos los servicios):
```json
{ "timestamp": "2026-11-05T12:00:00Z", "status": 409, "code": "INSUFFICIENT_STOCK",
  "message": "Mensaje breve y genérico en inglés", "path": "/api/orders", "details": [ { "field": "...", "message": "..." } ] }
```
`details` es opcional. Códigos: `VALIDATION_ERROR`(400), `INVALID_CREDENTIALS`(401), `UNAUTHORIZED`(401), `NOT_FOUND`(404), `USERNAME_TAKEN`(409), `EMAIL_TAKEN`(409), `SKU_ALREADY_EXISTS`(409), `INSUFFICIENT_STOCK`(409), `PRODUCT_INACTIVE`(409), `INVALID_STATUS_TRANSITION`(409), `REGISTRATION_DISABLED`(403), `SERVICE_UNAVAILABLE`(503), `INTERNAL_ERROR`(500).

**Paginación** (`?page=0&size=20&sort=campo,asc`): `{ "content": [...], "page": 0, "size": 20, "totalElements": 134, "totalPages": 7 }`. `size` ≤ 100; solo se permiten campos de orden de una lista blanca (si no, 400).

**Tipos:** ids = UUID string; fechas = ISO-8601 UTC (`Instant`); dinero = número decimal con 2 decimales (`BigDecimal`), nunca `double`; cantidades = entero.

## 6. API por servicio

### 6.1 auth-service (:8081) — BD `auth`
Tabla `users(id, username UNIQUE, email UNIQUE, password_hash, created_at)`; `username` y `email` guardados en minúsculas.

| Método y ruta | Body | Respuesta |
|---|---|---|
| `POST /api/auth/register` | `{username 3–32 [a-zA-Z0-9_.-], email ≤120, password 8–72}` | 201 `AuthResponse` |
| `POST /api/auth/login` | `{username, password}` | 200 `AuthResponse` |
| `GET /api/auth/me` (JWT) | — | 200 `User` |

```ts
AuthResponse = { accessToken: string, tokenType: "Bearer", expiresIn: number /*segundos*/, user: User }
User = { id: string, username: string, email: string }
```

### 6.2 inventory-service (:8082) — BD `inventory`
Tablas: `products(id, sku UNIQUE, name, description, price, stock, low_stock_threshold, active, created_at, updated_at, version)` con CHECK `stock >= 0`, `price >= 0`; `stock_movements(id, product_id, delta, type[RESERVE|RELEASE|ADJUSTMENT], reason, order_ref, created_at)`, único `(order_ref, product_id, type)` cuando `order_ref` no es nulo (garantiza idempotencia).

```ts
Product = { id, sku, name, description: string|null, price: number, stock: number,
            lowStockThreshold: number, active: boolean, createdAt, updatedAt }
```
`sku`: 1–40, `[A-Za-z0-9._-]`, guardado en mayúsculas. `name` 1–120. `description` ≤ 1000. `price` 0–999999.99. `stock` ≥ 0. `lowStockThreshold` ≥ 0 (defecto 5).

| Método y ruta | Body / query | Respuesta |
|---|---|---|
| `GET /api/products` | `search` (sku o nombre), `lowStock=true`, `page,size,sort` (lista blanca: `name,sku,price,stock,createdAt`) | 200 `Page<Product>` (solo `active=true`) |
| `GET /api/products/summary` | — | 200 `{ totalProducts, lowStockCount, totalUnits }` |
| `GET /api/products/{id}` | — | 200 `Product` / 404 |
| `POST /api/products` | `{sku,name,description?,price,stock,lowStockThreshold?}` | 201 `Product` / 409 `SKU_ALREADY_EXISTS` |
| `PUT /api/products/{id}` | `{sku,name,description?,price,lowStockThreshold}` (el stock NO se cambia aquí) | 200 `Product` |
| `POST /api/products/{id}/stock-adjustments` | `{delta: entero ≠ 0, reason 1–200}` | 200 `Product` / 409 `INSUFFICIENT_STOCK` si dejaría stock < 0 |
| `DELETE /api/products/{id}` | — | 204 (baja lógica: `active=false`) |

Internos (`X-Internal-Key`):

| Ruta | Body | Respuesta |
|---|---|---|
| `POST /internal/stock/reserve` | `{orderRef: uuid, items:[{productId, quantity ≥1}]}` | 200 `{items:[{productId, sku, name, unitPrice, quantity}]}`; 409 `INSUFFICIENT_STOCK` con `details:[{productId, requested, available}]`; 404 `NOT_FOUND` (productId inexistente); 409 `PRODUCT_INACTIVE` |
| `POST /internal/stock/release` | `{orderRef: uuid}` | 204 |

Reglas de reserva/liberación:
- **Todo o nada** en una transacción. Bloquear las filas en orden ascendente de `productId` (evita interbloqueos). Prohibido leer-modificar-escribir sin bloqueo o sin actualización condicional (`stock >= q`).
- **Idempotente por `orderRef`**: reservar dos veces el mismo `orderRef` devuelve el resultado original sin volver a descontar; liberar dos veces (o liberar algo nunca reservado) es no-op con 204. Liberar revierte exactamente lo registrado en `stock_movements`, incluso si el producto está dado de baja.
- **Stock bajo:** si tras una reserva o un ajuste negativo se cumple `stockAntes > lowStockThreshold && stockDespués <= lowStockThreshold`, enviar notificación `LOW_STOCK` (broadcast) **después del commit**, best-effort.

### 6.3 order-service (:8083) — BD `orders`
Tablas: `orders(id, customer_name, notes, status, total, created_by, created_by_username, created_at, updated_at, version)`, `order_items(id, order_id, product_id, sku, product_name, unit_price, quantity, line_total)` (instantánea del producto al crear: el historial no cambia si el producto cambia).

Estados: `PENDING`, `CONFIRMED`, `SHIPPED`, `DELIVERED`, `CANCELLED`. Transiciones válidas: `PENDING→CONFIRMED|CANCELLED`, `CONFIRMED→SHIPPED|CANCELLED`, `SHIPPED→DELIVERED`. `DELIVERED` y `CANCELLED` son terminales. Cualquier otra ⇒ 409 `INVALID_STATUS_TRANSITION`.

```ts
OrderItem = { id, productId, sku, productName, unitPrice: number, quantity: number, lineTotal: number }
Order = { id, customerName, notes: string|null, status, total: number, items: OrderItem[],
          createdBy: string, createdByUsername: string, createdAt, updatedAt }
OrderSummary = { id, customerName, status, total, itemCount: number, createdByUsername, createdAt }
```

| Método y ruta | Body / query | Respuesta |
|---|---|---|
| `POST /api/orders` | `{customerName 1–120, notes? ≤500, items:[{productId, quantity 1–10000}]}` (1–50 ítems, sin `productId` repetido) | 201 `Order` |
| `GET /api/orders` | `status`, `page,size,sort` (lista blanca: `createdAt,total,status,customerName`) | 200 `Page<OrderSummary>` |
| `GET /api/orders/summary` | — | 200 `{ total, byStatus: { PENDING: n, CONFIRMED: n, SHIPPED: n, DELIVERED: n, CANCELLED: n } }` |
| `GET /api/orders/{id}` | — | 200 `Order` / 404 |
| `PATCH /api/orders/{id}/status` | `{status}` | 200 `Order` |

Flujo de creación: generar `orderId` → `POST inventory /internal/stock/reserve` con `orderRef=orderId` → guardar pedido `PENDING` con la instantánea devuelta (el `unitPrice` sale **siempre** de inventario, nunca del cliente) → notificar `NEW_ORDER`. Si guardar falla tras reservar ⇒ compensar con `release` (best-effort). Errores `INSUFFICIENT_STOCK`/`NOT_FOUND`/`PRODUCT_INACTIVE` de inventario se propagan tal cual (mismo código y `details`); inventario caído ⇒ 503.
Flujo de cancelación (`→CANCELLED`): validar transición → `release` (idempotente) → confirmar estado. Si `release` falla ⇒ 503 y el pedido no cambia. Limitación conocida (sin saga/outbox): si el commit falla después del `release`, el stock queda liberado con el pedido aún activo; reintentar la cancelación lo resuelve.
Notificaciones: `NEW_ORDER` (broadcast) al crear; `ORDER_STATUS_CHANGED` (dirigida a `createdBy`) en cada cambio de estado.

### 6.4 notification-service (:8084) — BD `notifications`
Tablas: `notifications(id, type, title, message, target_user_id NULL, actor_user_id NULL, entity_type, entity_id, created_at)`, `notification_reads(notification_id, user_id, read_at)` PK compuesta.

```ts
Notification = { id, type: "NEW_ORDER"|"LOW_STOCK"|"ORDER_STATUS_CHANGED", title, message,
                 targetUserId: string|null, actorUserId: string|null,
                 entityType: "ORDER"|"PRODUCT", entityId: string, createdAt, read: boolean }
```
`title` ≤ 120, `message` ≤ 500. **Idioma (decisión del orquestador tras revisión): `title` y `message` se escriben en español**, porque el frontend los pinta tal cual (como texto, nunca HTML); los mensajes de error de la API (`message` del §5) siguen en inglés y el frontend los traduce por `code`. No incluir datos personales del cliente: solo número corto de pedido, estado, SKU/nombre de producto. `targetUserId = null` ⇒ broadcast. `actorUserId` = quien provocó el evento (el frontend no muestra toast de eventos propios).

| Método y ruta | Descripción |
|---|---|
| `GET /api/notifications?unreadOnly=&page=&size=` | `Page<Notification>` visibles para el usuario actual (broadcast + dirigidas a él), más recientes primero; `read` calculado por usuario |
| `GET /api/notifications/unread-count` | `{ count }` |
| `POST /api/notifications/{id}/read` | 204 (solo si le es visible; idempotente) |
| `POST /api/notifications/read-all` | 204 |
| `POST /internal/notifications` (`X-Internal-Key`) | body `{type,title,message,targetUserId?,actorUserId?,entityType,entityId}` → persiste y emite por WebSocket → 201 |

## 7. WebSocket (notification-service)
- Endpoint **WebSocket nativo** (sin SockJS) `GET /ws`, protocolo STOMP 1.2. Sin autenticación en el handshake HTTP, pero: validar header `Origin` contra `ALLOWED_ORIGINS`, y cerrar la conexión si no llega un `CONNECT` válido en 10 s.
- Autenticación en el frame `CONNECT`: header `Authorization: Bearer <jwt>` (mismo secreto/`iss` que §3). Token ausente/inválido/expirado ⇒ frame `ERROR` y cierre. El `Principal.name` = `sub` (id de usuario).
- Destinos permitidos para `SUBSCRIBE`: `/topic/notifications` (broadcast) y `/user/queue/notifications` (dirigidas). Cualquier otro destino se rechaza; el cliente no puede `SEND` a destinos de aplicación (no hay entrantes).
- Cada mensaje es el JSON `Notification` de arriba (`read: false`). Heartbeat 10 s/10 s. Límite de tamaño de mensaje bajo (≤ 8 KB).
- Limitación conocida: el token no se revalida mientras la conexión sigue abierta; al expirar la sesión el frontend cierra la conexión.

## 8. Variables de entorno (valores reales solo en `.env`, ignorado por git; `.env.example` con marcadores)

| Variable | Usada por |
|---|---|
| `JWT_SECRET`, `JWT_ISSUER`(=`micro-inventory`), `JWT_EXPIRATION_MINUTES` | auth (firma+valida), gateway, inventory, order, notification (validan) |
| `INTERNAL_API_KEY` | inventory, order, notification |
| `AUTH_DB_PASSWORD`, `INVENTORY_DB_PASSWORD`, `ORDER_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD` | su BD y su servicio |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | cada servicio (ej. `jdbc:postgresql://auth-db:5432/auth`) |
| `INVENTORY_SERVICE_URL` (`http://inventory-service:8082`), `NOTIFICATION_SERVICE_URL` (`http://notification-service:8084`) | order; inventory (solo la segunda) |
| `ROUTE_AUTH_URI`, `ROUTE_INVENTORY_URI`, `ROUTE_ORDER_URI`, `ROUTE_NOTIFICATION_URI`, `ROUTE_NOTIFICATION_WS_URI` | gateway |
| `ALLOWED_ORIGINS` (defecto `http://localhost:3000,http://localhost:5173`) | notification |
| `REGISTRATION_ENABLED` (defecto `true`) | auth |

Los servicios no deben arrancar sin `JWT_SECRET` válido (≥ 32 chars) ni sin `INTERNAL_API_KEY` donde aplique: fallar con mensaje claro, **sin valores por defecto inseguros** en `application.yml`.

## 9. Healthchecks
Cada servicio expone `GET /actuator/health` (público, sin detalles) y su imagen define `HEALTHCHECK` con `wget -qO- http://localhost:<puerto>/actuator/health`.

## 10. Frontend
- Servido por nginx sin privilegios (`nginxinc/nginx-unprivileged`), puerto 3000 en el host. `location /api/` y `location /ws` hacen proxy a `api-gateway:8080` (con `Upgrade`/`Connection` para `/ws`). Mismo origen ⇒ el gateway no necesita CORS. En desarrollo, el proxy de Vite hace lo mismo hacia `http://localhost:8080`.
- Cabeceras de seguridad en nginx (CSP sin `unsafe-inline` para scripts, `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'`).
- Token JWT: en memoria + `sessionStorage` (por pestaña); ante 401 se limpia la sesión y se redirige a `/login`.
- UI **en español**; código, rutas y nombres en inglés. Modo claro/oscuro. Pantallas: `/login`, `/register`, `/` (panel con resúmenes), `/products` (lista, búsqueda, filtro stock bajo, crear/editar/baja, ajuste de stock), `/orders` (lista + filtro por estado), `/orders/new`, `/orders/:id` (detalle, avanzar estado, cancelar), campana de notificaciones con contador, historial, marcar leídas y *toasts* en tiempo real.
