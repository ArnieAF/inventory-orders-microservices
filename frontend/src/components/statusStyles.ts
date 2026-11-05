import type { OrderStatus } from '../api/types'
import { CheckCircleIcon, ClockIcon, SealCheckIcon, TruckIcon, XCircleIcon, type Icon } from './icons'

/** Color + icono + texto: el estado nunca depende solo del color. */
export const ORDER_STATUS_STYLE: Readonly<Record<OrderStatus, { badge: string; mark: string; text: string; icon: Icon }>> = {
  PENDING: { badge: 'bg-warn-soft text-warn-text', mark: 'bg-warn-mark', text: 'text-warn-text', icon: ClockIcon },
  CONFIRMED: { badge: 'bg-info-soft text-info-text', mark: 'bg-info-mark', text: 'text-info-text', icon: SealCheckIcon },
  SHIPPED: { badge: 'bg-ship-soft text-ship-text', mark: 'bg-ship-mark', text: 'text-ship-text', icon: TruckIcon },
  DELIVERED: { badge: 'bg-ok-soft text-ok-text', mark: 'bg-ok-mark', text: 'text-ok-text', icon: CheckCircleIcon },
  CANCELLED: { badge: 'bg-mute-soft text-mute-text', mark: 'bg-mute-mark', text: 'text-mute-text', icon: XCircleIcon },
}

/** Stock bajo: stock ≤ umbral (la misma regla que usa inventario para avisar). */
export function isLowStock(stock: number, threshold: number): boolean {
  return stock <= threshold
}
