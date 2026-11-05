# PLAN — Sistema de Inventario y Pedidos (microservicios)

> Proyecto de portafolio (2026, en proceso): plataforma full-stack de gestión de inventario y pedidos con microservicios.
> Contrato técnico detallado: [`docs/CONTRACT.md`](docs/CONTRACT.md).

## Objetivo
Java 17 + Spring Boot, dividido en servicios independientes de **autenticación, inventario y pedidos** comunicados a través de un **API Gateway** (Spring Cloud Gateway) con **JWT**. Frontend **React + TypeScript + Tailwind**. Comunicación **REST** entre servicios, **notificaciones en tiempo real (WebSocket)**, **PostgreSQL por servicio** y **Docker / Docker Compose** para orquestarlo todo.

## Decisiones del usuario (confirmadas el 2026-11-05)
| Tema | Decisión |
|---|---|
| Roles | **Un solo rol**: todo usuario autenticado puede hacer todo (herramienta interna de back-office) |
| Notificaciones en tiempo real | Nuevo pedido, stock bajo y cambio de estado del pedido |
| Idioma | UI en **español**, código/rutas/tablas en **inglés** |
| Build Java | **Solo Docker** (no hay JDK ni Maven locales; sí hay Docker 29, Node 24, Git) |

## Decisiones del orquestador (revisables)
- **5 servicios Spring + frontend**: `api-gateway`, `auth-service`, `inventory-service`, `order-service`, y un `notification-service` propio (los avisos nacen en inventario y pedidos; centralizarlos evita acoplar WebSocket a ambos). 4 PostgreSQL (uno por servicio con datos).
- Proyectos Maven **independientes**, sin librería compartida (independencia real de despliegue).
- Seguridad: gateway valida JWT y cada servicio lo revalida; llamadas internas con `X-Internal-Key` en `/internal/**` que el gateway nunca enruta.
- Stock consistente: reserva **todo-o-nada, idempotente por `orderRef`** y con filas bloqueadas en orden.
- Tests con **H2 modo PostgreSQL + Flyway** (sin Testcontainers); Postgres real se verifica en el conjunto con `docker compose`.
- Repo remoto **público** creado el 2026-11-05 en `https://github.com/ArnieAF/inventory-orders-microservices` (licencia MIT, **vacío**: todavía no se ha subido nada). El `origin` se añadirá al momento de publicar. Antes del primer push: (1) revisar `git ls-files` y el historial completo en busca de secretos, (2) usar el correo `noreply` de GitHub como identidad de los commits publicados, (3) tener `develop` integrado y verificado.
- **Política de `main` (decisión del usuario, 2026-11-05, reemplaza la regla "main solo lo que el usuario probó"):** `main` en GitHub debe mostrar avance real lo antes posible. Primer push a `main` (y `develop`) en cuanto las 6 ramas estén integradas en `develop` y el conjunto pase: tests de cada servicio en Docker, `docker compose build/up` y la prueba de humo, más el escaneo de secretos y la reescritura de autor. Ese primer `main` es **pre-revisión** y el README lo dirá con honestidad (qué está verificado y qué no). Después, la revisión (3 lentes) y las correcciones llegan como commits posteriores y `main` se actualiza tras cada integración verificada.
- GitHub Actions / despliegue automático: **no configurado** (fuera del MVP hasta que el usuario lo pida en el chat y diga el destino del despliegue).

## Alcance
**MVP (dentro):** registro/login JWT · CRUD de productos con baja lógica y ajuste de stock · crear pedidos con reserva de stock · estados del pedido con transiciones validadas y cancelación que libera stock · notificaciones WebSocket + historial/leídas · panel con resúmenes · todo levantable con `docker compose up`.

**Fuera del MVP:** refresh tokens, recuperación de contraseña, roles/permisos finos, rate limiting/bloqueo de cuenta, broker de mensajes (Kafka/RabbitMQ), saga/outbox transaccional, service discovery, observabilidad (métricas/trazas), HTTPS/certificados, CI/CD, despliegue en nube, multi-idioma, carrito/pagos/facturación.

## Riesgos conocidos
1. Cancelar un pedido no es transaccional entre servicios (sin saga/outbox): puede quedar stock liberado con el pedido activo si el commit falla; reintentar lo corrige. Documentado en el contrato.
2. Registro abierto + un solo rol: cualquiera que se registre administra todo. Mitigación: `REGISTRATION_ENABLED=false`.
3. El JWT no se revalida mientras el WebSocket sigue abierto.
4. Sin JDK local: la compilación depende de Docker (primera build lenta por descargas de Maven).
5. Los tests en H2 no detectan diferencias específicas de PostgreSQL; se cubren con la prueba de humo del conjunto.

## Reparto (Fase 1 — construcción en paralelo)
Cada agente en su rama y worktree (`.worktrees/<rama>`), carpetas disjuntas, desde `develop`. No hay remoto `origin`: los agentes commitean en su rama local y NO empujan.

| Rama | Agente (modelo) | Carpeta que posee |
|---|---|---|
| `feat/auth-gateway` | rol-backend (Sonnet) | `services/auth-service/`, `services/api-gateway/` |
| `feat/inventory` | rol-backend (Sonnet) | `services/inventory-service/` |
| `feat/orders` | rol-backend (Sonnet) | `services/order-service/` |
| `feat/notifications` | rol-backend (Sonnet) | `services/notification-service/` |
| `feat/frontend` | rol-frontend (Opus) | `frontend/` |
| `feat/infra` | rol-mecanico (Haiku) | `docker-compose.yml`, `.env.example`, `scripts/` |

Archivos compartidos (`PLAN.md`, `docs/`, `.gitignore`, `README.md`) los edita solo el orquestador.

Ciclo: construir → revisar (auditores Fable con 3 lentes: seguridad, UX, corrección) → corregir → integrar en `develop` (`--no-ff`) → verificar el conjunto (tests de cada servicio en Docker, `docker compose build/up`, prueba de humo, typecheck/lint/test/build del frontend).

## Estado (2026-11-05)
| Fase | Estado |
|---|---|
| 0. Plan + contrato + repo | ✅ hecho |
| 1. Construcción en paralelo (6 ramas) | ✅ hecho |
| 2. Revisión independiente (3 lentes) | 🟡 4 de 6 revisiones completas con verificación adversarial (orders, inventory, auth-service, api-gateway). notification-service y frontend: solo los revisores iniciales (sin verificación adversarial), la revisión se interrumpió por límite de uso |
| 3. Corrección | 🟡 orders, inventory y auth-service corregidos e integrados en `develop`. Pendiente: api-gateway (9 puntos), notification-service y frontend (ver [docs/NEXT-STEPS.md](docs/NEXT-STEPS.md)) |
| 4. Integración + verificación del conjunto | 🟡 el stack completo con lo ya corregido pasa la prueba de humo (58/58, directo y vía nginx). Falta repetirla tras las correcciones pendientes |
| 5. Informe + prueba manual del usuario | ⏳ pendiente |

Continuidad y lista priorizada de pendientes: [`docs/NEXT-STEPS.md`](docs/NEXT-STEPS.md).

## Registro de modelos
| Tarea | Modelo | Cómo se supo |
|---|---|---|
| Plan, contrato, integración, verificación | Sonnet 5.5 (orquestador) | Indicado por el entorno de la sesión |
| auth-service, api-gateway, inventory-service, order-service, notification-service (construcción y correcciones) | Sonnet 5.5 | Modelo fijado en la definición del agente `rol-backend`; los commits llevan `Co-Authored-By: Claude Sonnet 5.5` |
| frontend (React/TS/Tailwind, nginx) | Opus 5.5 | Modelo fijado en la definición del agente `rol-frontend`; commits con `Co-Authored-By: Claude Opus 5.5` |
| docker-compose, .env.example, scripts de entorno y tests | Haiku 5.5 | Modelo fijado en la definición del agente `rol-mecanico`; commit con `Co-Authored-By: Claude Haiku 5.5` |
| Revisión independiente (auditores y verificadores) | Fable | Parámetro de lanzamiento `model: fable` de los agentes de revisión; no hay otra confirmación del modelo real |

## Qué NO está verificado
- Uso manual en navegadores y dispositivos reales, lectores de pantalla, rendimiento y carga.
- Las correcciones de inventory y auth-service se ejecutaron en Docker (194 y 81 tests) y contra Postgres 16 real en la prueba de humo, pero la concurrencia fina (carreras reserva/liberación) solo se prueba con H2 en los tests.
- Una primera ejecución independiente de los tests de inventory y auth-service dio BUILD FAILURE sin que se llegara a conocer la causa; dos repeticiones posteriores pasaron. Se sospecha fragilidad por carga (tests con timeouts), sin confirmar.
- Revisión de notification-service y frontend sin verificación adversarial.
