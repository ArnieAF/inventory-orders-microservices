import { describe, expect, it } from 'vitest'
import type { Notification } from '../api/types'
import { createNotificationCenter, notificationHref, parseNotification } from './notificationCenter'

const ME = 'user-me'
const OTHER = 'user-other'

function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: 'n1',
    type: 'NEW_ORDER',
    title: 'Nuevo pedido',
    message: 'Ferretería Alcalá: 3 productos',
    targetUserId: null,
    actorUserId: OTHER,
    entityType: 'ORDER',
    entityId: 'order-1',
    createdAt: '2026-11-05T10:00:00Z',
    read: false,
    ...overrides,
  }
}

describe('centro de notificaciones', () => {
  it('suma al contador y muestra toast de eventos de otros', () => {
    const center = createNotificationCenter()
    center.setUnreadCount(2)
    const result = center.ingest(notification(), ME)
    expect(result).toMatchObject({ status: 'accepted', toast: true })
    expect(center.getState().unreadCount).toBe(3)
    expect(center.getState().toasts).toHaveLength(1)
    expect(center.getState().toasts[0]).toMatchObject({ title: 'Nuevo pedido', href: '/orders/order-1' })
  })

  it('no muestra toast de eventos propios (pero sí cuentan como no leídos, igual que en el servidor)', () => {
    const center = createNotificationCenter()
    center.setUnreadCount(0)
    const result = center.ingest(notification({ actorUserId: ME }), ME)
    expect(result).toMatchObject({ status: 'accepted', toast: false })
    expect(center.getState().toasts).toHaveLength(0)
    expect(center.getState().unreadCount).toBe(1)
  })

  it('descarta duplicados que llegan por los dos canales', () => {
    const center = createNotificationCenter()
    center.setUnreadCount(0)
    center.ingest(notification({ id: 'dup' }), ME)
    const second = center.ingest(notification({ id: 'dup', targetUserId: ME }), ME)
    expect(second.status).toBe('duplicate')
    expect(center.getState().unreadCount).toBe(1)
    expect(center.getState().toasts).toHaveLength(1)
  })

  it('no vuelve a contar notificaciones ya cargadas en el historial', () => {
    const center = createNotificationCenter()
    center.setUnreadCount(5)
    center.rememberSeen(['n-hist'])
    expect(center.ingest(notification({ id: 'n-hist' }), ME).status).toBe('duplicate')
    expect(center.getState().unreadCount).toBe(5)
  })

  it('rechaza mensajes con forma inesperada', () => {
    const center = createNotificationCenter()
    expect(center.ingest(null, ME).status).toBe('invalid')
    expect(center.ingest({ ...notification(), type: 'HACK' }, ME).status).toBe('invalid')
    expect(center.ingest({ ...notification(), actorUserId: 42 }, ME).status).toBe('invalid')
    expect(center.getState().toasts).toHaveLength(0)
  })

  it('marcar leída resta uno (sin bajar de 0) y marcar todas pone 0', () => {
    const center = createNotificationCenter()
    center.setUnreadCount(1)
    center.markRead('n1', true)
    center.markRead('n2', true)
    expect(center.getState().unreadCount).toBe(0)
    center.setUnreadCount(9)
    center.markRead('n3', false)
    expect(center.getState().unreadCount).toBe(9)
    center.markAllRead()
    expect(center.getState().unreadCount).toBe(0)
  })

  it('mientras no se conoce el contador del servidor no inventa uno', () => {
    const center = createNotificationCenter()
    center.ingest(notification(), ME)
    expect(center.getState().unreadCount).toBeNull()
  })

  it('limita los toasts visibles y permite cerrarlos', () => {
    const center = createNotificationCenter({ maxToasts: 2 })
    center.ingest(notification({ id: 'a', title: 'A' }), ME)
    center.ingest(notification({ id: 'b', title: 'B' }), ME)
    center.ingest(notification({ id: 'c', title: 'C' }), ME)
    expect(center.getState().toasts.map((t) => t.title)).toEqual(['B', 'C'])
    const first = center.getState().toasts[0]
    if (first) center.dismissToast(first.id)
    expect(center.getState().toasts.map((t) => t.title)).toEqual(['C'])
  })

  it('reset al cerrar sesión borra contador, avisos y memoria de duplicados', () => {
    const center = createNotificationCenter()
    center.setUnreadCount(3)
    center.ingest(notification(), ME)
    center.reset()
    expect(center.getState()).toEqual({ unreadCount: null, toasts: [], connection: 'idle' })
    center.setUnreadCount(0)
    expect(center.ingest(notification(), ME).status).toBe('accepted')
  })
})

describe('parseNotification y enlaces', () => {
  it('recorta textos largos y conserva el texto tal cual (sin interpretar HTML)', () => {
    const parsed = parseNotification({ ...notification(), title: '<img src=x onerror=alert(1)>' + 'x'.repeat(200) })
    expect(parsed?.title).toHaveLength(120)
    expect(parsed?.title.startsWith('<img')).toBe(true)
  })

  it('lleva al pedido o al listado de productos', () => {
    expect(notificationHref({ type: 'ORDER_STATUS_CHANGED', entityType: 'ORDER', entityId: 'a/b' })).toBe('/orders/a%2Fb')
    expect(notificationHref({ type: 'LOW_STOCK', entityType: 'PRODUCT', entityId: 'p' })).toBe('/products?lowStock=true')
  })
})
