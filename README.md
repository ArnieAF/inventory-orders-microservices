# Sistema de Inventario y Pedidos — Arquitectura de Microservicios

> **EN —** Full-stack inventory & order management platform built as microservices: Java 17 / Spring Boot services (auth, inventory, orders, notifications) behind a Spring Cloud Gateway with JWT, a React + TypeScript + Tailwind frontend, real-time notifications over WebSocket (STOMP), one PostgreSQL database per service, all orchestrated with Docker Compose. UI in Spanish, code in English.
>
> **Status: preliminary snapshot (pre-review).** The whole stack runs and passes an end-to-end smoke test, but an independent security / UX / correctness review is still being applied. See [Estado del proyecto](#estado-del-proyecto).

Plataforma full-stack de gestión de inventario y pedidos basada en microservicios (Java 17, Spring Boot). Servicios independientes de autenticación, inventario, pedidos y notificaciones, comunicados mediante un API Gateway (Spring Cloud Gateway) con autenticación JWT. Frontend en React, TypeScript y Tailwind CSS. Comunicación REST entre servicios, notificaciones en tiempo real (WebSocket), PostgreSQL por servicio y contenedores Docker / Docker Compose para orquestar el entorno.

## Arquitectura

```mermaid
flowchart LR
  U[Navegador] --> N["frontend (nginx) :3000"]
  N -->|"/api · /ws"| G["api-gateway :8080"]
  G --> A["auth-service :8081"] --> ADB[(auth-db)]
  G --> I["inventory-service :8082"] --> IDB[(inventory-db)]
  G --> O["order-service :8083"] --> ODB[(order-db)]
  G --> S["notification-service :8084"] --> SDB[(notification-db)]
  O -. "REST /internal" .-> I
  O -. "REST /internal" .-> S
  I -. "REST /internal" .-> S
  S == "WebSocket STOMP" ==> U
```

| Servicio | Puerto interno | Responsabilidad |
|---|---|---|
| `frontend` | 3000 (host) | React + TS + Tailwind servido por nginx; proxy de `/api` y `/ws` al gateway |
| `api-gateway` | 8080 (host) | Spring Cloud Gateway: valida JWT, enruta, bloquea `/internal/**` |
| `auth-service` | 8081 | Registro, login y emisión de JWT (HS256, BCrypt) |
| `inventory-service` | 8082 | Productos, ajustes de stock, reserva/liberación de stock idempotente |
| `order-service` | 8083 | Pedidos y máquina de estados; reserva stock vía REST |
| `notification-service` | 8084 | Historial de notificaciones y WebSocket STOMP en tiempo real |

Solo `frontend` (3000) y `api-gateway` (8080) publican puertos, y únicamente en `127.0.0.1`. Cada servicio con datos tiene su propio PostgreSQL 16 (migraciones con Flyway). El contrato técnico completo (rutas, tipos, códigos de error, JWT, WebSocket, variables de entorno) está en [`docs/CONTRACT.md`](docs/CONTRACT.md).

## Funcionalidades

- Registro e inicio de sesión con JWT; todo usuario autenticado puede operar (un solo rol, pensado como herramienta interna).
- Productos: alta, edición, baja lógica, búsqueda, filtro de stock bajo y ajustes de stock con motivo.
- Pedidos: creación con reserva de stock **todo-o-nada**, estados `PENDING → CONFIRMED → SHIPPED → DELIVERED` o `CANCELLED` (cancelar libera el stock). El precio siempre lo fija inventario, nunca el cliente.
- Notificaciones en tiempo real: nuevo pedido, stock bajo y cambio de estado del pedido, con historial y marcado de leídas.
- Interfaz en español, modo claro/oscuro y diseño responsive.

## Puesta en marcha

Requisitos: Docker Desktop (Docker Compose v2). No hace falta JDK, Maven ni Node: todo se compila dentro de Docker.

```bash
# 1) Generar .env con secretos aleatorios (no se versiona; no imprime los secretos)
#    Windows (PowerShell):
./scripts/init-env.ps1
#    Git Bash / Linux / macOS:
bash scripts/init-env.sh

# 2) Construir y levantar todo (la primera vez descarga dependencias de Maven y tarda varios minutos)
docker compose up -d --build

# 3) Abrir la aplicación
#    http://localhost:3000   → regístrate y empieza
```

Parar: `docker compose down` (añade `-v` para borrar también los datos).

> Los puertos 3000 y 8080 deben estar libres en tu máquina.

## Pruebas

```bash
# Tests de los servicios Java dentro de Docker (todos, o indica uno)
./scripts/test-java.ps1            # PowerShell      (o: bash scripts/test-java.sh)
./scripts/test-java.ps1 order-service

# Tests del frontend
cd frontend && npm ci && npm run typecheck && npm run lint && npm test

# Prueba de humo del conjunto (requiere el stack levantado; BASE_URL opcional)
node scripts/smoke-test.mjs                          # contra el gateway (8080)
BASE_URL=http://localhost:3000 node scripts/smoke-test.mjs   # a través de nginx
```

La prueba de humo recorre registro/login, productos, pedidos con consistencia de stock, 12 pedidos simultáneos por 5 unidades (no hay sobreventa), notificaciones en tiempo real por WebSocket y los controles de seguridad del gateway.

## Seguridad (resumen)

- JWT HS256 validado en el gateway **y** revalidado en cada servicio (defensa en profundidad).
- Rutas `/internal/**` solo con `X-Internal-Key` (comparación en tiempo constante); el gateway nunca las enruta ni deja pasar cabeceras internas desde fuera.
- WebSocket: autenticación en el frame STOMP `CONNECT`, lista blanca de `Origin`, destinos restringidos.
- Sin secretos en el repositorio: se generan en `.env` (ignorado por git). Los servicios no arrancan sin secretos válidos.
- Contenedores sin root, puertos publicados solo en loopback, CSP estricta en nginx.

## Limitaciones conocidas (fuera del MVP)

Sin refresh tokens, recuperación de contraseña, roles finos, rate limiting, broker de mensajes ni saga/outbox (la cancelación de pedidos no es transaccional entre servicios), service discovery, observabilidad, HTTPS ni CI/CD. El registro es abierto (`REGISTRATION_ENABLED=false` lo cierra). El JWT no se revalida mientras un WebSocket sigue abierto.

## Estado del proyecto

**Versión preliminar (pre-revisión).**

- **Verificado ejecutando:** el stack completo (4 PostgreSQL 16 reales + 5 servicios + nginx) levanta sano en Docker y la prueba de humo de 55 comprobaciones pasa tanto contra el gateway como a través de nginx. Cada servicio trae su propia suite de pruebas (JUnit con H2 en modo PostgreSQL + Flyway; Vitest en el frontend).
- **En curso:** una revisión independiente (seguridad, UX/accesibilidad y corrección) ya encontró defectos reales, sobre todo condiciones de carrera en la reserva/cancelación de stock, y sus correcciones se están aplicando. Los resúmenes irán a `docs/`.
- **No verificado todavía:** uso manual en navegadores y dispositivos reales, lectores de pantalla, rendimiento/carga, y ejecución repetida de todas las suites tras las correcciones.

Plan, decisiones y reparto del trabajo: [`PLAN.md`](PLAN.md).

## Estructura

```
services/        auth-service · api-gateway · inventory-service · order-service · notification-service
frontend/        React + TypeScript + Tailwind + Vite (+ Dockerfile y nginx.conf)
docs/CONTRACT.md Contrato técnico compartido por todos los servicios
scripts/         init-env, test-java, smoke-test
docker-compose.yml · .env.example
```

## Licencia

[MIT](LICENSE) © 2026 Arnie Adriano
