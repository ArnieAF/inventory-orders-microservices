# Cómo continuar este proyecto con una IA (prompt listo para pegar)

Pensado para retomar el trabajo con cualquier asistente de IA, incluso uno gratuito y sin contexto previo.

## Cómo usarlo

1. Clona el repositorio y ábrelo en tu editor: `git clone https://github.com/ArnieAF/inventory-orders-microservices.git`
2. Si tu IA **puede leer archivos del proyecto** (extensión del editor, CLI…): pega el bloque de abajo y listo.
3. Si tu IA **solo funciona en un chat**: pega primero el contenido de estos tres archivos (en GitHub: abre el archivo → botón **Raw** → copiar todo), uno por mensaje, y después pega el bloque:
   `README.md`, `docs/NEXT-STEPS.md` y `docs/CONTRACT.md`.
4. Pídele cambios **de uno en uno** y comprueba cada uno con las verificaciones antes de aceptarlo.

## Prompt

```
Eres mi asistente de desarrollo. Vamos a continuar un proyecto ya avanzado SIN romper nada. Trabaja despacio, con cambios pequeños, y no inventes: si no estás seguro, pregúntame.

PROYECTO
Sistema de Inventario y Pedidos con microservicios (Java 17 + Spring Boot 3.5, API Gateway con JWT, frontend React + TypeScript + Tailwind servido por nginx, PostgreSQL 16 por servicio, WebSocket STOMP, Docker Compose). UI en español, código en inglés.
Repo público: https://github.com/ArnieAF/inventory-orders-microservices (rama main).

LEE PRIMERO (en este orden): README.md, docs/NEXT-STEPS.md y docs/CONTRACT.md. El contrato es la fuente de verdad. NEXT-STEPS.md tiene el estado, los pendientes priorizados (archivo y causa de cada uno), cómo verificar y las reglas.

LÍNEA BASE (antes de tocar nada)
1. bash scripts/init-env.sh   (o ./scripts/init-env.ps1)  -> crea .env con secretos aleatorios. NUNCA lo subas ni lo muestres.
2. docker compose up -d --build   (solo hace falta Docker; la primera vez tarda varios minutos)
3. node scripts/smoke-test.mjs   -> debe dar "58 correctas, 0 fallidas". Si algo más usa el puerto 3000, usa BASE_URL=http://127.0.0.1:3000 o cambia FRONTEND_PORT en .env.

TU TRABAJO
Empieza por docs/NEXT-STEPS.md sección 3.1 (api-gateway), de arriba abajo (P0 antes que P1 y P2). Para CADA punto:
a) Lee el código indicado y confirma que el problema es real; si no lo es, dímelo y sáltalo.
b) Propón el cambio mínimo (diff) y espera mi OK.
c) Añade o ajusta un test que falle sin el cambio y pase con él.
d) Verifica: tests del servicio en Docker (comando en NEXT-STEPS §1) y luego docker compose up -d --build <servicio> y la prueba de humo.
e) Haz commit pequeño en español en una rama fix/<servicio>-<tema>. Nunca push --force ni cambios directos en main; main solo recibe lo que pasa las verificaciones.
Cuando termines el gateway, sigue con notification-service (3.2) y frontend (3.3).

REGLAS
- Secretos jamás en el repo ni en el chat; no me pidas contraseñas ni tokens.
- Identidad de commits: git config user.name "Arnie Adriano" y user.email "162141521+ArnieAF@users.noreply.github.com" (no uses ni muestres otro correo).
- No cambies docs/CONTRACT.md ni rutas, campos o códigos de error sin avisarme.
- Distingue siempre: verificado (ejecutado) / revisado a mano / sospechado, y dime qué NO pudiste comprobar.
- Si un test falla una vez y luego pasa, no lo ignores: guarda el log completo y dímelo.

EMPIEZA AHORA con la línea base y dime el resultado de la prueba de humo antes de tocar código.
```

## Prueba manual de la aplicación (para hacer tú mismo)

1. `bash scripts/init-env.sh` y `docker compose up -d --build`; abre http://localhost:3000 (o http://127.0.0.1:3000), regístrate y abre otra ventana (incógnito) con otro usuario.
2. Crea 2–3 productos (uno con umbral de stock bajo alto).
3. El usuario A crea un pedido: el stock baja y el usuario B recibe el aviso de "nuevo pedido" al instante.
4. El usuario B confirma el pedido: A recibe el aviso de cambio de estado. Cancela otro pedido: el stock vuelve.
5. Pide más unidades de las que hay: debe marcar la línea con lo disponible.
6. Prueba el modo oscuro, un ancho de móvil (~390 px) y la campana de notificaciones con el teclado (Tab).
