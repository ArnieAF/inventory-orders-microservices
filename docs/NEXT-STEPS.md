# Próximos pasos y traspaso

Documento de continuidad para quien retome el proyecto (persona o asistente de IA) **sin el contexto de las sesiones anteriores**. Léelo junto a [`README.md`](../README.md), [`PLAN.md`](../PLAN.md) y [`CONTRACT.md`](CONTRACT.md) (el contrato es la fuente de verdad).

Última actualización: 2026-11-05.

## 1. Línea base: comprueba que todo funciona antes de tocar nada

```bash
bash scripts/init-env.sh            # (o ./scripts/init-env.ps1) crea .env con secretos aleatorios; no lo subas ni lo muestres
docker compose up -d --build        # primera vez: varios minutos
node scripts/smoke-test.mjs                         # contra el gateway (:8080): debe dar "58 correctas, 0 fallidas"
BASE_URL=http://127.0.0.1:3000 node scripts/smoke-test.mjs   # a través de nginx (usa 127.0.0.1 si algo más escucha en :3000)
```

Si los puertos 3000/8080 están ocupados, cambia `FRONTEND_PORT` / `GATEWAY_PORT` en `.env` (y añade el origen a `ALLOWED_ORIGINS`).
Tests de un servicio en Docker (sin JDK local):

```bash
docker run --rm -v "$PWD/services/<servicio>:/app" -v micro-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-17 mvn -B test
# frontend:  cd frontend && npm ci && npm run typecheck && npm run lint && npm test
```

## 2. Estado por componente

| Componente | Revisión independiente | Correcciones | Tests Java (Docker) |
|---|---|---|---|
| `order-service` | completa y verificada | ✅ integradas | 375, verificado |
| `inventory-service` | completa y verificada | ✅ integradas (lápida por `orderRef`, migración V2, timeouts, límites) | 194, verificado |
| `auth-service` | completa y verificada | ✅ integradas | 81, verificado |
| `api-gateway` | completa y verificada | ⏳ **pendientes (§3.1)** | 122 (sin cambios) |
| `notification-service` | solo revisores iniciales, **sin verificación adversarial** | ⏳ pendientes (§3.2) | 233 (cifra del autor, no repetida) |
| `frontend` | solo revisores iniciales, **sin verificación adversarial** | ⏳ pendientes (§3.3) | 91 (cifra del autor, no repetida) |

"Verificado" = ejecutado en Docker durante la integración. Los hallazgos sin verificación adversarial pueden incluir falsos positivos: **lee el código antes de cambiar nada**.

## 3. Pendientes, por prioridad

### 3.1 api-gateway (`services/api-gateway/`) — empieza por aquí
1. **P0** `config/JwtConfig.java` (~l.48): el decoder acepta JWT sin `exp` (reproducido: `GET /api/products` → 200 con un token solo con `iss`/`sub`). Añade un validador que exija `exp`, `sub` e `iss` (copia el `requiredClaims` de `auth-service`). Test en `GatewayRoutingTest` con `TestJwt.mint(..., exp=null)` → 401.
2. **P0** `web/RequestGuardWebFilter.java`: una petición con cabecera `Upgrade` fuera de `/ws/**` provoca 500 y traza ERROR. Responde 400 `VALIDATION_ERROR` (sin distinguir mayúsculas) + test.
3. **P1** `application.yml`: añade `default-filters: RequestSize` (1 MB; solo mira `Content-Length`, no cubre chunked) y `httpclient.websocket.max-frame-payload-length: 8192`. Tests: 413 en formato común sin llegar al backend; cierre con frame WS > 8 KB.
4. **P1** `SecurityConfig.java`: `POST /api/auth/login/` y `/register/` (con barra final) se reenvían sin JWT. Haz el match exacto sin barra final (o 404).
5. **P1** `web/PathPolicy.java`: no rechaza doble codificación (`%25xx`). Rechazar con 404 + test en `PathPolicyTest`.
6. **P1** `error/ErrorMapping.java`: backend que responde algo no-HTTP → 500; debe ser 503 `SERVICE_UNAVAILABLE`.
7. **P2** tests de 405/413/415 (conservan su status con `VALIDATION_ERROR`) y de 403 (code `UNAUTHORIZED`); aserción de `timestamp` en `GatewayRoutingTest` (~l.162) debe parsear el `Instant`; log WARN (sin tokens) si falla el handshake WS.

### 3.2 notification-service (`services/notification-service/`)
1. **P0** `service/PageParams.java`: `?page=2147483647&size=100` → 500 (el offset desborda `int`). Rechazar con 400 cuando `(long) page * size > Integer.MAX_VALUE` + test.
2. **P0** `config/AppProperties.java`: exigir `INTERNAL_API_KEY` ≥ 16 caracteres al arrancar (contrato §8), mensaje claro sin imprimir el valor; ajustar claves de test.
3. **P1** `ws/StompAuthChannelInterceptor.java` (~l.98): tras autenticar, `accessor.removeNativeHeader("Authorization")` para que el JWT no viaje en los eventos/logs; test.
4. **P1** `api/dto/CreateNotificationRequest.java`: validar coherencia `type`/`entityType` (`LOW_STOCK`⇒`PRODUCT`; `NEW_ORDER`/`ORDER_STATUS_CHANGED`⇒`ORDER`) → 400.
5. **P2** carrera entre el temporizador de CONNECT y la autenticación (`ws/WebSocketSessionTracker.java` ~l.55); `read-all` fila a fila (`service/NotificationService.java` ~l.129: pásalo a un único `INSERT … SELECT`); varios tests WebSocket "negativos" pasan por la razón equivocada (`WebSocketIntegrationTest` ~l.635-708: afirma el cierre *antes* del timeout o un frame ERROR); jar de la imagen propiedad de root y solo lectura (`Dockerfile` ~l.18); ruido de logs por clientes no autenticados.
6. **Descartado**: la cabecera STOMP `Authorization` es sensible a mayúsculas, pero el frontend ya envía exactamente `Authorization` (`frontend/src/realtime/stompConnection.ts`).

### 3.3 frontend (`frontend/`)
1. **P0** `nginx.conf` + `nginx/security-headers.conf`: `/api/`, `/ws`, `/healthz`, los 403 y los errores generados por nginx salen **sin CSP ni cabeceras de seguridad** (un `add_header` dentro de un `location` anula el nivel `server`). Mueve el `include` al nivel `server` y mantenlo en los `location` que añaden `Cache-Control`. Usa `location ^~ /api/` y `location = /ws` (hoy la regex `~ /\.` gana sobre `/api/` y `/wsfoo` llega al gateway).
2. **P0** `pages/orders/NewOrderPage.tsx` (~l.110): `INSUFFICIENT_STOCK` sin `details[].productId` falla en silencio. Muestra un banner genérico con el mensaje de `ERROR_MESSAGES.INSUFFICIENT_STOCK` + test con `details=[]` y `details=[{field,message}]`.
3. **P1** validación (`domain/validation.ts`): tope de stock y de |ajuste| = **1 000 000 000** (hoy 2 147 483 647) y **contraseña medida en bytes UTF-8 (8–72)** con `TextEncoder`, no en caracteres (contrato §6.1).
4. **P1** `layout/NotificationBell.tsx` (~l.106): en móvil (320–390 px) el panel puede salirse del viewport por la izquierda; en `max-sm` colócalo respecto al viewport. `components/Dialog.tsx` (~l.56): arrastrar una selección de texto hasta el fondo cierra el formulario y pierde lo escrito; cierra solo si `pointerdown` y `click` ocurren ambos sobre el fondo. `auth/session.ts` (~l.106): avisa antes de caducar la sesión (5 min) o persiste el borrador del pedido en `sessionStorage`.
5. **P2** CSP `connect-src 'self'` (sin `ws: wss:` a cualquier host); `Cache-Control: immutable` también sale en los 404 de `/assets/` (quita `always`); `OrdersPage` con página fuera de rango muestra "Aún no hay pedidos"; `PRODUCT_INACTIVE`/`NOT_FOUND` sin `productId` dejan el banner "quita los marcados" sin líneas marcadas; "Ajustar cantidades" quita líneas sin avisar; confirmación para "Marcar como entregado" (terminal); búsqueda de Productos no se sincroniza con la URL al navegar atrás; toasts quedan detrás de un diálogo modal; foco se pierde al deshabilitar el botón en `loading`; `prefers-reduced-motion` no cubre `transition`; fila de filtros de Productos desborda a 320 px; fijar imágenes base por versión/digest; moneda (hoy sin símbolo, `MONEY_CURRENCY` en `domain/format.ts`: **decisión del dueño del proyecto**).

### 3.4 Cierre de la fase
- Repite la prueba de humo (ampliándola con lo nuevo: JWT sin `exp` → 401, `Upgrade` en ruta HTTP → 400, `page` enorme → 400).
- Guarda en `docs/` un resumen de cada auditoría en un commit aparte: qué revisó, qué encontró, qué se corrigió y qué fuentes lo respaldan (los informes completos de las revisiones no están en el repo).
- Tras tu prueba manual (README → "Puesta en marcha"), considera una CI en GitHub Actions que compile y pruebe en cada push. **No está configurada**; un despliegue automático requiere decidir el destino.

## 4. Reglas para continuar sin romper nada

1. **Una rama por tarea** (`fix/<servicio>-<tema>`), commits pequeños en español. **Nunca** `push --force` ni cambios directos en `main`.
2. **Antes de fusionar a `main`**: tests del servicio tocado en Docker → `docker compose up -d --build <servicio>` → prueba de humo por los dos caminos (:8080 y :3000). Si falla, no se fusiona.
3. **No cambies `docs/CONTRACT.md`** ni rutas, nombres de campos o códigos de error sin dejarlo anotado; los demás servicios y el frontend dependen de ellos.
4. **Secretos**: jamás en el repo ni en conversaciones. `.env` está ignorado por git; no lo muestres ni lo pegues.
5. **Identidad de commits**: `git config user.name "Arnie Adriano"` y `git config user.email "162141521+ArnieAF@users.noreply.github.com"`. Indica en el commit qué asistente escribió el código (`Co-Authored-By`).
6. Distingue siempre **verificado** (ejecutado), **revisado a mano** y **sospechado**, y deja dicho qué no pudiste comprobar.
7. **Un test que falla una vez y pasa después no se ignora**: una ejecución independiente de inventory y auth-service dio `BUILD FAILURE` sin causa conocida y luego pasó dos veces. Si lo ves, guarda el log completo (hay tests con timeouts y arranques de Tomcat sensibles a la carga).

## 5. Mapa rápido del código

| Dónde | Qué |
|---|---|
| `services/api-gateway/.../web/RequestGuardWebFilter.java`, `PathPolicy.java`, `security/SecurityConfig.java`, `config/JwtConfig.java` | Política de rutas, cabeceras internas, JWT en el gateway |
| `services/auth-service/.../user/AuthService.java`, `config/JwtConfig.java`, `security/TokenService.java` | Registro/login, emisión y validación de JWT |
| `services/inventory-service/.../stock/StockService.java` | Reserva/liberación idempotente (cabecera `stock_reservations`, lápida, timeouts) |
| `services/order-service/.../service/OrderService.java`, `OrderStore.java` | Pedidos, máquina de estados, cancelación con reintento |
| `services/notification-service/.../ws/*`, `service/NotificationService.java` | WebSocket STOMP, guardián de Origin, historial por usuario |
| `frontend/src/api`, `auth`, `realtime`, `pages`, `domain` | Cliente tipado, sesión, STOMP, pantallas, lógica pura |
| `frontend/nginx.conf`, `frontend/nginx/security-headers.conf` | Proxy a `/api` y `/ws`, cabeceras de seguridad |

## 6. Decisiones cerradas (no reabrir sin hablarlo)

Un solo rol; registro abierto controlado por `REGISTRATION_ENABLED`; notificaciones de nuevo pedido y stock bajo en difusión y de cambio de estado al creador, con textos en español y sin datos personales; reserva de stock todo-o-nada e idempotente por `orderRef`, con el precio siempre fijado por inventario; JWT HS256 con `JWT_SECRET` como bytes UTF-8, validado en el gateway y en cada servicio; `/internal/**` solo con `X-Internal-Key` y nunca enrutado por el gateway; tests Java con H2 en modo PostgreSQL + Flyway (sin Testcontainers); fuera del MVP: refresh tokens, recuperación de contraseña, rate limiting, broker de mensajes, saga/outbox, observabilidad, HTTPS y CI/CD.
