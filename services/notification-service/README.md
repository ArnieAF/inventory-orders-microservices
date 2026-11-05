# notification-service (:8084)

Notificaciones del sistema de inventario y pedidos: persistencia (PostgreSQL), historial/leídas por usuario (REST) y
entrega en tiempo real por WebSocket (STOMP nativo). Contrato: `docs/CONTRACT.md` §6.4 y §7.

Paquete base `com.microinv.notifications`. Java 17, Spring Boot 3.5.16, Flyway.

## Variables de entorno

| Variable | Obligatoria | Descripción |
|---|---|---|
| `JWT_SECRET` | sí | Secreto HS256 (≥ 32 caracteres, se usan sus bytes UTF-8 tal cual). Sin valor por defecto: el arranque falla. |
| `JWT_ISSUER` | no (`micro-inventory`) | `iss` exigido en los tokens. |
| `INTERNAL_API_KEY` | sí | Clave de `X-Internal-Key` para `/internal/**`. Sin valor por defecto: el arranque falla. |
| `ALLOWED_ORIGINS` | no (`http://localhost:3000,http://localhost:5173`) | Orígenes permitidos en el handshake de `/ws` (lista separada por comas; sin `*`). |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | sí | Conexión a PostgreSQL (`jdbc:postgresql://notification-db:5432/notifications`). |
| `app.websocket.connect-timeout` (propiedad, no es variable del contrato) | no (`10s`) | Tiempo máximo para recibir un `CONNECT` válido tras abrir el WebSocket (las pruebas lo bajan). |

## Endpoints

| Ruta | Autenticación | Descripción |
|---|---|---|
| `GET /api/notifications?unreadOnly=&page=&size=&sort=createdAt,desc` | JWT | `Page<Notification>` visibles para el usuario (difusión + dirigidas a él). `size` 1–100. |
| `GET /api/notifications/unread-count` | JWT | `{ "count": n }` |
| `POST /api/notifications/{id}/read` | JWT | 204; 404 si no existe o no le es visible; idempotente |
| `POST /api/notifications/read-all` | JWT | 204; solo marca lo visible para el usuario |
| `POST /internal/notifications` | `X-Internal-Key` | Persiste y emite por WebSocket → 201 con la `Notification` |
| `GET /actuator/health` | pública | Sin detalles |
| `GET /ws` (WebSocket) | JWT en el frame `CONNECT` | STOMP 1.2 nativo (sin SockJS) |

## WebSocket

1. Handshake `GET /ws` con `Origin` permitido. Sin cabecera `Authorization` en el handshake.
2. Primer frame `CONNECT` con `Authorization: Bearer <jwt>` en los 10 s siguientes (si no, se cierra la conexión).
   Token ausente/inválido/caducado: frame `ERROR` y cierre. `Principal.name` = `sub`.
3. `SUBSCRIBE` solo a `/topic/notifications` (difusión) y `/user/queue/notifications` (dirigidas). Cualquier otro
   destino: `ERROR` y cierre. El cliente no puede hacer `SEND` (no hay destinos de aplicación).
4. Heartbeat 10 s/10 s; mensaje entrante máximo 8 KB.

Cada mensaje es el JSON `Notification` del contrato con `read: false`.

## Pruebas

H2 en modo PostgreSQL + Flyway, sin Docker socket (desde esta carpeta):

```
docker run --rm -v "<ruta-abs>:/app" -v micro-m2-notifications:/root/.m2 -w /app maven:3.9-eclipse-temurin-17 mvn -B test
```
