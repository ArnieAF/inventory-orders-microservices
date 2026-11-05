import type { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '../api/queryKeys'
import type { Notification } from '../api/types'

/**
 * Qué datos quedan obsoletos con cada evento:
 * - NEW_ORDER: pedidos y resúmenes; también productos (la reserva descuenta stock).
 * - ORDER_STATUS_CHANGED: pedidos; productos (cancelar libera stock).
 * - LOW_STOCK: productos y resumen de inventario.
 * El historial de notificaciones siempre; el contador ya se actualizó en local.
 */
export function invalidateForNotification(queryClient: QueryClient, notification: Pick<Notification, 'type'>): void {
  void queryClient.invalidateQueries({ queryKey: ['notifications', 'list'] })
  switch (notification.type) {
    case 'NEW_ORDER':
    case 'ORDER_STATUS_CHANGED':
      void queryClient.invalidateQueries({ queryKey: queryKeys.orders.all })
      void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
      break
    case 'LOW_STOCK':
      void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
      break
  }
}

/** Tras reconectar pudo perderse cualquier evento: se re-sincroniza todo lo visible. */
export function resyncAfterReconnect(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: queryKeys.notifications.all })
  void queryClient.invalidateQueries({ queryKey: queryKeys.orders.all })
  void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
}
