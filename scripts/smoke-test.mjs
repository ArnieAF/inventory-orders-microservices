#!/usr/bin/env node
// Prueba de humo del conjunto (cero dependencias, Node >= 22).
// Requiere el stack levantado: `docker compose up -d --build` y el gateway en BASE_URL.
// Uso: node scripts/smoke-test.mjs   (BASE_URL opcional, por defecto http://localhost:8080)
// Crea usuarios y datos con prefijo aleatorio en la base real del stack local; no borra nada.

import { randomBytes } from 'node:crypto';

const BASE = (process.env.BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws';
const RUN = randomBytes(4).toString('hex');

let passed = 0;
const failures = [];

function check(name, condition, detail) {
  if (condition) {
    passed += 1;
    console.log(`  ok   ${name}`);
  } else {
    failures.push(name);
    console.log(`  FAIL ${name}${detail !== undefined ? ` -> ${JSON.stringify(detail)}` : ''}`);
  }
}

async function api(method, path, { token, body, headers = {} } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { _raw: text.slice(0, 200) };
  }
  return { status: res.status, json };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- Cliente STOMP mínimo sobre WebSocket nativo --------------------------------------------
// Un navegador siempre envía `Origin`; sin él, el cliente Netty del gateway inventa el del servicio destino
// (http://notification-service:8084) y el guardián de Origin lo rechaza. Se simula el del frontend.
const WS_ORIGIN = process.env.WS_ORIGIN ?? 'http://localhost:3000';

function stompConnect(token, origin = WS_ORIGIN) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL, { headers: { Origin: origin } });
    const received = [];
    let connected = false;
    let closed = false;
    const timer = setTimeout(() => reject(new Error('timeout CONNECT')), 8000);

    const finish = (value, isError) => {
      clearTimeout(timer);
      isError ? reject(value) : resolve(value);
    };

    ws.onopen = () => {
      const auth = token ? `Authorization:Bearer ${token}\n` : '';
      ws.send(`CONNECT\naccept-version:1.2\nheart-beat:0,0\n${auth}\n\0`);
    };
    ws.onclose = () => {
      closed = true;
      if (!connected) finish(new Error('closed'), true);
    };
    ws.onerror = () => {};
    ws.onmessage = (ev) => {
      for (const raw of String(ev.data).split('\0')) {
        const frame = raw.replace(/^\n+/, '');
        if (!frame) continue;
        const [head, ...rest] = frame.split('\n\n');
        const lines = head.split('\n');
        const command = lines[0];
        if (command === 'CONNECTED') {
          connected = true;
          ws.send('SUBSCRIBE\nid:b\ndestination:/topic/notifications\n\n\0');
          ws.send('SUBSCRIBE\nid:u\ndestination:/user/queue/notifications\n\n\0');
          finish(client, false);
        } else if (command === 'ERROR') {
          if (!connected) finish(new Error('ERROR frame'), true);
        } else if (command === 'MESSAGE') {
          try {
            received.push(JSON.parse(rest.join('\n\n')));
          } catch {
            /* ignora cuerpos no JSON */
          }
        }
      }
    };

    const client = {
      received,
      isClosed: () => closed,
      async waitFor(predicate, ms = 5000) {
        const end = Date.now() + ms;
        while (Date.now() < end) {
          const hit = received.find(predicate);
          if (hit) return hit;
          await sleep(100);
        }
        return undefined;
      },
      close: () => ws.close(),
    };
  });
}

async function connectFails(token, origin) {
  try {
    const c = await stompConnect(token, origin);
    c.close();
    return false;
  } catch {
    return true;
  }
}

// --- Datos ----------------------------------------------------------------------------------
async function newUser(label) {
  const username = `smoke_${label}_${RUN}`;
  const password = randomBytes(12).toString('base64url');
  const email = `${username}@example.test`;
  const reg = await api('POST', '/api/auth/register', { body: { username, email, password } });
  return { username, email, password, reg };
}

async function newProduct(token, suffix, { price = '10.50', stock = 10, lowStockThreshold = 2 } = {}) {
  const res = await api('POST', '/api/products', {
    token,
    body: { sku: `SMK-${RUN}-${suffix}`, name: `Smoke ${suffix} ${RUN}`, price, stock, lowStockThreshold },
  });
  return res;
}

const stockOf = async (token, id) => (await api('GET', `/api/products/${id}`, { token })).json?.stock;

async function main() {
  console.log(`Prueba de humo contra ${BASE} (run ${RUN})\n`);

  console.log('[1] Gateway y seguridad básica');
  const health = await api('GET', '/actuator/health');
  check('gateway /actuator/health = 200', health.status === 200, health.status);
  const noToken = await api('GET', '/api/products');
  check('GET /api/products sin token = 401', noToken.status === 401, noToken.status);
  check('401 con formato de error común', typeof noToken.json?.code === 'string' && noToken.json?.status === 401, noToken.json);
  const badToken = await api('GET', '/api/products', { token: 'esto.no.es.un.jwt' });
  check('token basura = 401', badToken.status === 401, badToken.status);
  const unknownRoute = await api('GET', '/api/otra-cosa');
  check('ruta fuera del contrato = 404 (o 401)', [404, 401].includes(unknownRoute.status), unknownRoute.status);

  console.log('\n[2] Autenticación');
  const A = await newUser('a');
  check('registro A = 201 con accessToken', A.reg.status === 201 && !!A.reg.json?.accessToken, A.reg.status);
  const B = await newUser('b');
  check('registro B = 201', B.reg.status === 201, B.reg.status);
  const dup = await api('POST', '/api/auth/register', { body: { username: A.username, email: `x_${A.email}`, password: A.password } });
  check('username duplicado = 409 USERNAME_TAKEN', dup.status === 409 && dup.json?.code === 'USERNAME_TAKEN', dup.json);
  const short = await api('POST', '/api/auth/register', { body: { username: 'ab', email: 'no-es-email', password: '123' } });
  check('registro inválido = 400 VALIDATION_ERROR', short.status === 400 && short.json?.code === 'VALIDATION_ERROR', short.json);
  const badLogin = await api('POST', '/api/auth/login', { body: { username: A.username, password: 'incorrecta-xyz' } });
  const ghostLogin = await api('POST', '/api/auth/login', { body: { username: `nadie_${RUN}`, password: 'incorrecta-xyz' } });
  check('login erróneo = 401 INVALID_CREDENTIALS', badLogin.status === 401 && badLogin.json?.code === 'INVALID_CREDENTIALS', badLogin.json);
  check('usuario inexistente da el mismo error', ghostLogin.status === 401 && ghostLogin.json?.message === badLogin.json?.message, ghostLogin.json);
  const login = await api('POST', '/api/auth/login', { body: { username: A.username, password: A.password } });
  check('login correcto = 200', login.status === 200 && login.json?.tokenType === 'Bearer', login.status);
  const tokA = login.json?.accessToken;
  const tokB = B.reg.json?.accessToken;
  const me = await api('GET', '/api/auth/me', { token: tokA });
  check('GET /api/auth/me devuelve al usuario', me.status === 200 && me.json?.username === A.username, me.json);
  const idA = me.json?.id;
  const idB = (await api('GET', '/api/auth/me', { token: tokB })).json?.id;

  console.log('\n[3] Inventario');
  const internal = await api('POST', '/internal/stock/reserve', { token: tokA, body: { orderRef: crypto.randomUUID(), items: [] } });
  check('/internal/** no se enruta (404)', internal.status === 404, internal.status);
  const internal2 = await api('POST', '/internal/stock/reserve', { token: tokA, headers: { 'X-Internal-Key': 'adivinando' }, body: {} });
  check('/internal/** con X-Internal-Key falsa tampoco (404)', internal2.status === 404, internal2.status);
  const p1 = await newProduct(tokA, 'P1', { price: '10.50', stock: 10, lowStockThreshold: 2 });
  check('crear producto = 201', p1.status === 201 && p1.json?.stock === 10, p1.json);
  const p2 = await newProduct(tokA, 'P2', { price: '3.25', stock: 6, lowStockThreshold: 5 });
  check('crear segundo producto = 201', p2.status === 201, p2.status);
  const dupSku = await newProduct(tokA, 'P1');
  check('SKU duplicado = 409', dupSku.status === 409 && dupSku.json?.code === 'SKU_ALREADY_EXISTS', dupSku.json);
  const search = await api('GET', `/api/products?search=SMK-${RUN}&size=50`, { token: tokA });
  check('búsqueda por SKU encuentra 2', search.status === 200 && search.json?.totalElements === 2, search.json?.totalElements);
  const badSort = await api('GET', '/api/products?sort=passwordHash,asc', { token: tokA });
  check('sort fuera de lista blanca = 400', badSort.status === 400, badSort.status);
  const adj = await api('POST', `/api/products/${p1.json.id}/stock-adjustments`, { token: tokA, body: { delta: -3, reason: 'smoke' } });
  check('ajuste -3 deja stock 7', adj.status === 200 && adj.json?.stock === 7, adj.json);
  const adjBad = await api('POST', `/api/products/${p1.json.id}/stock-adjustments`, { token: tokA, body: { delta: -999, reason: 'smoke' } });
  check('ajuste que deja stock negativo = 409', adjBad.status === 409, adjBad.json);
  const psum = await api('GET', '/api/products/summary', { token: tokA });
  check('summary de productos', psum.status === 200 && typeof psum.json?.totalProducts === 'number', psum.json);

  console.log('\n[4] Pedidos y consistencia de stock');
  const idP1 = p1.json.id;
  const idP2 = p2.json.id;
  const sockA = await stompConnect(tokA);
  const sockB = await stompConnect(tokB);
  check('WebSocket STOMP conecta con JWT (A y B)', true);

  const o1 = await api('POST', '/api/orders', {
    token: tokA,
    body: { customerName: 'Cliente Smoke', notes: 'prueba', items: [{ productId: idP1, quantity: 2, unitPrice: '0.01' }, { productId: idP2, quantity: 2 }] },
  });
  const o1ok = o1.status === 201;
  check('crear pedido = 201 (o 400 si rechaza campos extra)', o1ok || o1.status === 400, o1.json);
  let order = o1.json;
  if (!o1ok) {
    const retry = await api('POST', '/api/orders', {
      token: tokA,
      body: { customerName: 'Cliente Smoke', notes: 'prueba', items: [{ productId: idP1, quantity: 2 }, { productId: idP2, quantity: 2 }] },
    });
    check('pedido sin campos extra = 201', retry.status === 201, retry.json);
    order = retry.json;
  }
  check('precio tomado de inventario, total 2*10.50 + 2*3.25 = 27.50', Number(order?.total) === 27.5, order?.total);
  check('estado inicial PENDING, createdBy = A', order?.status === 'PENDING' && order?.createdBy === idA, order);
  check('stock descontado (P1 5, P2 4)', (await stockOf(tokA, idP1)) === 5 && (await stockOf(tokA, idP2)) === 4);

  const tooMany = await api('POST', '/api/orders', { token: tokA, body: { customerName: 'X', items: [{ productId: idP1, quantity: 1000 }] } });
  check('stock insuficiente = 409 INSUFFICIENT_STOCK con details', tooMany.status === 409 && tooMany.json?.code === 'INSUFFICIENT_STOCK' && Array.isArray(tooMany.json?.details), tooMany.json);
  check('pedido fallido no descuenta nada', (await stockOf(tokA, idP1)) === 5);
  const mixed = await api('POST', '/api/orders', { token: tokA, body: { customerName: 'X', items: [{ productId: idP2, quantity: 1 }, { productId: idP1, quantity: 1000 }] } });
  check('todo-o-nada: ítem válido + inválido no descuenta el válido', mixed.status === 409 && (await stockOf(tokA, idP2)) === 4, mixed.json);
  const dupItems = await api('POST', '/api/orders', { token: tokA, body: { customerName: 'X', items: [{ productId: idP1, quantity: 1 }, { productId: idP1, quantity: 1 }] } });
  check('ítems repetidos = 400', dupItems.status === 400, dupItems.json);

  // Notificaciones en tiempo real
  const newOrderMsg = await sockB.waitFor((n) => n.type === 'NEW_ORDER' && n.entityId === order?.id);
  check('B recibe NEW_ORDER (broadcast) con actorUserId = A', !!newOrderMsg && newOrderMsg.actorUserId === idA, newOrderMsg);
  const lowStockMsg = await sockB.waitFor((n) => n.type === 'LOW_STOCK' && n.entityId === idP2);
  check('B recibe LOW_STOCK de P2 (6 -> 4, umbral 5)', !!lowStockMsg, lowStockMsg);

  const orderId = order?.id;
  const badTransition = await api('PATCH', `/api/orders/${orderId}/status`, { token: tokB, body: { status: 'DELIVERED' } });
  check('PENDING -> DELIVERED = 409 INVALID_STATUS_TRANSITION', badTransition.status === 409 && badTransition.json?.code === 'INVALID_STATUS_TRANSITION', badTransition.json);
  const confirm = await api('PATCH', `/api/orders/${orderId}/status`, { token: tokB, body: { status: 'CONFIRMED' } });
  check('B confirma el pedido de A', confirm.status === 200 && confirm.json?.status === 'CONFIRMED', confirm.json);
  const statusMsg = await sockA.waitFor((n) => n.type === 'ORDER_STATUS_CHANGED' && n.entityId === orderId);
  check('A (creador) recibe ORDER_STATUS_CHANGED dirigida', !!statusMsg && statusMsg.targetUserId === idA, statusMsg);
  await sleep(1200);
  check('B NO recibe la notificación dirigida a A', !sockB.received.some((n) => n.type === 'ORDER_STATUS_CHANGED' && n.entityId === orderId));

  const list = await api('GET', '/api/orders?status=CONFIRMED&size=100', { token: tokB });
  check('B ve el pedido de A en el listado (un solo rol)', list.status === 200 && list.json?.content?.some((o) => o.id === orderId), list.status);
  const osum = await api('GET', '/api/orders/summary', { token: tokA });
  check('summary de pedidos con byStatus', osum.status === 200 && typeof osum.json?.byStatus?.CONFIRMED === 'number', osum.json);

  const cancel = await api('PATCH', `/api/orders/${orderId}/status`, { token: tokA, body: { status: 'CANCELLED' } });
  check('cancelar pedido CONFIRMED = 200', cancel.status === 200 && cancel.json?.status === 'CANCELLED', cancel.json);
  check('cancelar libera el stock (P1 7, P2 6)', (await stockOf(tokA, idP1)) === 7 && (await stockOf(tokA, idP2)) === 6);
  const again = await api('PATCH', `/api/orders/${orderId}/status`, { token: tokA, body: { status: 'CANCELLED' } });
  check('cancelar dos veces = 409 y no libera doble', again.status === 409 && (await stockOf(tokA, idP1)) === 7, again.json);

  console.log('\n[5] Concurrencia: no se sobrevende');
  const pc = await newProduct(tokA, 'RACE', { price: '1.00', stock: 5, lowStockThreshold: 0 });
  const results = await Promise.all(
    Array.from({ length: 12 }, (_, i) =>
      api('POST', '/api/orders', { token: i % 2 ? tokA : tokB, body: { customerName: `Race ${i}`, items: [{ productId: pc.json.id, quantity: 1 }] } }),
    ),
  );
  const okCount = results.filter((r) => r.status === 201).length;
  const conflictCount = results.filter((r) => r.status === 409).length;
  check('12 pedidos simultáneos por 5 unidades: exactamente 5 aceptados', okCount === 5, { okCount, conflictCount, other: results.map((r) => r.status) });
  check('los demás reciben 409', conflictCount === 12 - okCount, conflictCount);
  check('stock final = 0 (nunca negativo)', (await stockOf(tokA, pc.json.id)) === 0);

  console.log('\n[6] Historial de notificaciones (REST)');
  const histA = await api('GET', '/api/notifications?size=50', { token: tokA });
  check('A lista sus notificaciones', histA.status === 200 && histA.json?.content?.some((n) => n.type === 'ORDER_STATUS_CHANGED'), histA.status);
  const histB = await api('GET', '/api/notifications?size=100', { token: tokB });
  check('B no ve notificaciones dirigidas a A', histB.status === 200 && !histB.json?.content?.some((n) => n.targetUserId === idA), histB.status);
  const unread = await api('GET', '/api/notifications/unread-count', { token: tokA });
  check('unread-count > 0 para A', unread.status === 200 && unread.json?.count > 0, unread.json);
  const broadcast = histA.json?.content?.find((n) => n.targetUserId === null);
  if (broadcast) {
    const mark = await api('POST', `/api/notifications/${broadcast.id}/read`, { token: tokA });
    check('A marca una difusión como leída = 204', mark.status === 204, mark.status);
    const bView = (await api('GET', '/api/notifications?size=100', { token: tokB })).json?.content?.find((n) => n.id === broadcast.id);
    check('la misma difusión sigue sin leer para B', bView?.read === false, bView);
  }
  const readAll = await api('POST', '/api/notifications/read-all', { token: tokA });
  const unread2 = await api('GET', '/api/notifications/unread-count', { token: tokA });
  check('read-all deja el contador de A en 0', readAll.status === 204 && unread2.json?.count === 0, unread2.json);

  console.log('\n[7] WebSocket: autenticación');
  check('CONNECT sin token es rechazado', await connectFails(undefined));
  check('CONNECT con token manipulado es rechazado', await connectFails(tokA.slice(0, -4) + 'AAAA'));
  check('handshake desde un Origin no permitido es rechazado (aunque el token sea válido)', await connectFails(tokA, 'http://evil.example'));
  sockA.close();
  sockB.close();

  console.log(`\nResultado: ${passed} correctas, ${failures.length} fallidas`);
  if (failures.length) {
    console.log('Fallidas:\n - ' + failures.join('\n - '));
    process.exit(1);
  }
}

main().catch((err) => {
  console.error('\nError inesperado en la prueba de humo:', err?.message ?? err);
  process.exit(2);
});
