import { describe, expect, it } from 'vitest'
import { ORDER_STATUSES, type OrderStatus } from '../api/types'
import { availableActions, canCancel, canTransition, isTerminal, nextForwardStatus } from './orderStatus'

describe('máquina de estados del pedido (contrato §6.3)', () => {
  const valid: Array<[OrderStatus, OrderStatus]> = [
    ['PENDING', 'CONFIRMED'],
    ['PENDING', 'CANCELLED'],
    ['CONFIRMED', 'SHIPPED'],
    ['CONFIRMED', 'CANCELLED'],
    ['SHIPPED', 'DELIVERED'],
  ]

  it('acepta exactamente las transiciones del contrato', () => {
    for (const from of ORDER_STATUSES) {
      for (const to of ORDER_STATUSES) {
        const expected = valid.some(([a, b]) => a === from && b === to)
        expect(canTransition(from, to), `${from} → ${to}`).toBe(expected)
      }
    }
  })

  it('DELIVERED y CANCELLED son terminales', () => {
    expect(isTerminal('DELIVERED')).toBe(true)
    expect(isTerminal('CANCELLED')).toBe(true)
    expect(isTerminal('SHIPPED')).toBe(false)
  })

  it('un pedido enviado ya no se puede cancelar', () => {
    expect(canCancel('PENDING')).toBe(true)
    expect(canCancel('CONFIRMED')).toBe(true)
    expect(canCancel('SHIPPED')).toBe(false)
  })

  it('el botón principal lleva al siguiente estado del camino feliz', () => {
    expect(nextForwardStatus('PENDING')).toBe('CONFIRMED')
    expect(nextForwardStatus('CONFIRMED')).toBe('SHIPPED')
    expect(nextForwardStatus('SHIPPED')).toBe('DELIVERED')
    expect(nextForwardStatus('DELIVERED')).toBeNull()
    expect(nextForwardStatus('CANCELLED')).toBeNull()
  })

  it('acciones disponibles por estado', () => {
    expect(availableActions('PENDING')).toEqual({ forward: 'CONFIRMED', cancel: true })
    expect(availableActions('SHIPPED')).toEqual({ forward: 'DELIVERED', cancel: false })
    expect(availableActions('CANCELLED')).toEqual({ forward: null, cancel: false })
  })
})
