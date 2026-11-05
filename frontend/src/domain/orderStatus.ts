import type { OrderStatus } from '../api/types'

/** Máquina de estados del contrato (§6.3). DELIVERED y CANCELLED son terminales. */
export const ORDER_TRANSITIONS: Readonly<Record<OrderStatus, readonly OrderStatus[]>> = {
  PENDING: ['CONFIRMED', 'CANCELLED'],
  CONFIRMED: ['SHIPPED', 'CANCELLED'],
  SHIPPED: ['DELIVERED'],
  DELIVERED: [],
  CANCELLED: [],
}

/** Camino feliz, en orden, para dibujar el progreso. */
export const ORDER_FLOW: readonly OrderStatus[] = ['PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED']

export const ORDER_STATUS_LABEL: Readonly<Record<OrderStatus, string>> = {
  PENDING: 'Pendiente',
  CONFIRMED: 'Confirmado',
  SHIPPED: 'Enviado',
  DELIVERED: 'Entregado',
  CANCELLED: 'Cancelado',
}

/** Texto del botón que lleva AL estado indicado. */
export const ORDER_ACTION_LABEL: Readonly<Record<OrderStatus, string>> = {
  PENDING: 'Volver a pendiente',
  CONFIRMED: 'Confirmar pedido',
  SHIPPED: 'Marcar como enviado',
  DELIVERED: 'Marcar como entregado',
  CANCELLED: 'Cancelar pedido',
}

export function canTransition(from: OrderStatus, to: OrderStatus): boolean {
  return ORDER_TRANSITIONS[from].includes(to)
}

export function isTerminal(status: OrderStatus): boolean {
  return ORDER_TRANSITIONS[status].length === 0
}

/** Siguiente estado del camino feliz (el que no es cancelar), o null. */
export function nextForwardStatus(status: OrderStatus): OrderStatus | null {
  return ORDER_TRANSITIONS[status].find((next) => next !== 'CANCELLED') ?? null
}

export function canCancel(status: OrderStatus): boolean {
  return canTransition(status, 'CANCELLED')
}

/** Posición en el camino feliz (-1 si está cancelado). */
export function flowIndex(status: OrderStatus): number {
  return ORDER_FLOW.indexOf(status)
}

export interface OrderActions {
  forward: OrderStatus | null
  cancel: boolean
}

export function availableActions(status: OrderStatus): OrderActions {
  return { forward: nextForwardStatus(status), cancel: canCancel(status) }
}
