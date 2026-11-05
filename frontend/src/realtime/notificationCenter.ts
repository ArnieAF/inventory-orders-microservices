import {
  NOTIFICATION_ENTITY_TYPES,
  NOTIFICATION_TYPES,
  type Notification,
  type NotificationEntityType,
  type NotificationType,
} from '../api/types'

export type ToastTone = 'info' | 'success' | 'warning' | 'error'

export interface ToastItem {
  id: string
  tone: ToastTone
  title: string
  message?: string
  href?: string
  hrefLabel?: string
  /** Si viene de una notificación, su tipo (para el icono). */
  notificationType?: NotificationType
}

export type ConnectionStatus = 'idle' | 'connecting' | 'online' | 'offline'

export interface NotificationCenterState {
  /** `null` mientras no se conoce el valor del servidor. */
  unreadCount: number | null
  toasts: readonly ToastItem[]
  connection: ConnectionStatus
}

export type IngestStatus = 'accepted' | 'duplicate' | 'invalid'

export interface IngestResult {
  status: IngestStatus
  notification: Notification | null
  /** Se mostró toast (no se muestra para eventos propios). */
  toast: boolean
}

export interface NotificationCenter {
  getState: () => NotificationCenterState
  subscribe: (listener: () => void) => () => void
  /** Valor autoritativo del servidor (`GET /unread-count`). */
  setUnreadCount: (count: number) => void
  /** Mensaje en tiempo real (JSON ya parseado o crudo). */
  ingest: (raw: unknown, currentUserId: string | null) => IngestResult
  /** Ids ya conocidos por el historial, para no contarlos dos veces si llegan por WebSocket. */
  rememberSeen: (ids: Iterable<string>) => void
  markRead: (id: string, wasUnread: boolean) => void
  markAllRead: () => void
  pushToast: (toast: Omit<ToastItem, 'id'>) => string
  dismissToast: (id: string) => void
  setConnection: (status: ConnectionStatus) => void
  reset: () => void
}

const TITLE_MAX = 120
const MESSAGE_MAX = 500

function isOneOf<T extends string>(values: readonly T[], value: unknown): value is T {
  return typeof value === 'string' && (values as readonly string[]).includes(value)
}

function nullableString(value: unknown): string | null | undefined {
  if (value === null || value === undefined) return null
  return typeof value === 'string' ? value : undefined
}

/** Valida el JSON de una notificación (§6.4). Cualquier campo inesperado ⇒ null. */
export function parseNotification(raw: unknown): Notification | null {
  if (typeof raw !== 'object' || raw === null) return null
  const value = raw as Record<string, unknown>
  if (typeof value.id !== 'string' || value.id.length === 0) return null
  if (!isOneOf<NotificationType>(NOTIFICATION_TYPES, value.type)) return null
  if (!isOneOf<NotificationEntityType>(NOTIFICATION_ENTITY_TYPES, value.entityType)) return null
  if (typeof value.title !== 'string' || typeof value.message !== 'string') return null
  if (typeof value.entityId !== 'string' || typeof value.createdAt !== 'string') return null
  const targetUserId = nullableString(value.targetUserId)
  const actorUserId = nullableString(value.actorUserId)
  if (targetUserId === undefined || actorUserId === undefined) return null
  return {
    id: value.id,
    type: value.type,
    title: value.title.slice(0, TITLE_MAX),
    message: value.message.slice(0, MESSAGE_MAX),
    targetUserId,
    actorUserId,
    entityType: value.entityType,
    entityId: value.entityId,
    createdAt: value.createdAt,
    read: value.read === true,
  }
}

/** Destino al que lleva una notificación. */
export function notificationHref(notification: Pick<Notification, 'type' | 'entityType' | 'entityId'>): string {
  if (notification.entityType === 'ORDER') return `/orders/${encodeURIComponent(notification.entityId)}`
  return notification.type === 'LOW_STOCK' ? '/products?lowStock=true' : '/products'
}

export const NOTIFICATION_TYPE_LABEL: Readonly<Record<NotificationType, string>> = {
  NEW_ORDER: 'Pedido nuevo',
  LOW_STOCK: 'Stock bajo',
  ORDER_STATUS_CHANGED: 'Cambio de estado',
}

function toastFor(notification: Notification): Omit<ToastItem, 'id'> {
  return {
    tone: notification.type === 'LOW_STOCK' ? 'warning' : 'info',
    title: notification.title || NOTIFICATION_TYPE_LABEL[notification.type],
    message: notification.message,
    href: notificationHref(notification),
    hrefLabel: notification.entityType === 'ORDER' ? 'Ver pedido' : 'Ver productos',
    notificationType: notification.type,
  }
}

export interface NotificationCenterOptions {
  maxToasts?: number
  maxSeen?: number
  createId?: () => string
}

export function createNotificationCenter(options: NotificationCenterOptions = {}): NotificationCenter {
  const { maxToasts = 3, maxSeen = 500 } = options
  let sequence = 0
  const createId = options.createId ?? (() => `toast-${++sequence}`)

  const listeners = new Set<() => void>()
  /** Conjunto ordenado por inserción: se recortan los más antiguos al pasar de `maxSeen`. */
  const seen = new Set<string>()
  let state: NotificationCenterState = { unreadCount: null, toasts: [], connection: 'idle' }

  function update(next: Partial<NotificationCenterState>) {
    state = { ...state, ...next }
    for (const listener of listeners) listener()
  }

  function remember(id: string) {
    seen.add(id)
    while (seen.size > maxSeen) {
      const oldest = seen.values().next().value
      if (oldest === undefined) break
      seen.delete(oldest)
    }
  }

  function pushToast(toast: Omit<ToastItem, 'id'>): string {
    const id = createId()
    const toasts = [...state.toasts, { ...toast, id }]
    update({ toasts: toasts.slice(Math.max(0, toasts.length - maxToasts)) })
    return id
  }

  return {
    getState: () => state,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    setUnreadCount(count) {
      const safe = Number.isFinite(count) ? Math.max(0, Math.floor(count)) : 0
      if (safe !== state.unreadCount) update({ unreadCount: safe })
    },
    ingest(raw, currentUserId) {
      const notification = parseNotification(raw)
      if (!notification) return { status: 'invalid', notification: null, toast: false }
      if (seen.has(notification.id)) return { status: 'duplicate', notification, toast: false }
      remember(notification.id)

      if (!notification.read && state.unreadCount !== null) {
        update({ unreadCount: state.unreadCount + 1 })
      }
      const own = currentUserId !== null && notification.actorUserId === currentUserId
      if (!own) pushToast(toastFor(notification))
      return { status: 'accepted', notification, toast: !own }
    },
    rememberSeen(ids) {
      for (const id of ids) remember(id)
    },
    markRead(_id, wasUnread) {
      if (wasUnread && state.unreadCount !== null && state.unreadCount > 0) {
        update({ unreadCount: state.unreadCount - 1 })
      }
    },
    markAllRead() {
      update({ unreadCount: 0 })
    },
    pushToast,
    dismissToast(id) {
      if (state.toasts.some((toast) => toast.id === id)) {
        update({ toasts: state.toasts.filter((toast) => toast.id !== id) })
      }
    },
    setConnection(connection) {
      if (connection !== state.connection) update({ connection })
    },
    reset() {
      seen.clear()
      update({ unreadCount: null, toasts: [], connection: 'idle' })
    },
  }
}
