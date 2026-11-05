import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import type { Order, OrderStatus } from '../../api/types'
import { makeOrder } from '../../test/fixtures'
import { errorResponse, jsonResponse, mockFetch, renderPage, signInTestUser } from '../../test/utils'
import { OrderDetailPage } from './OrderDetailPage'

const ORDER_ID = '3f2a9c1b-7d4e-4a1b-9c2d-123456789abc'

function renderDetail() {
  return renderPage(<OrderDetailPage />, { path: '/orders/:id', initialEntry: `/orders/${ORDER_ID}` })
}

function serveOrder(order: Order, onPatch?: (status: OrderStatus) => Response) {
  let current = order
  return mockFetch({
    [`GET /api/orders/${ORDER_ID}`]: () => jsonResponse(current),
    [`PATCH /api/orders/${ORDER_ID}/status`]: ({ body }) => {
      const status = (body as { status: OrderStatus }).status
      if (onPatch) return onPatch(status)
      current = { ...current, status }
      return jsonResponse(current)
    },
  })
}

describe('OrderDetailPage', () => {
  beforeEach(() => signInTestUser())

  it('muestra la instantánea de ítems, el total y los datos del pedido', async () => {
    serveOrder(makeOrder())
    renderDetail()

    expect(await screen.findByRole('heading', { level: 1, name: 'Pedido #3F2A9C1B' })).toBeInTheDocument()
    const items = await screen.findByRole('table')
    expect(within(items).getByText('Caja de cartón doble canal 60x40')).toBeInTheDocument()
    expect(within(items).getByText('CAJ-0042')).toBeInTheDocument()
    expect(screen.getAllByText('47,00').length).toBeGreaterThan(0)
    expect(screen.getByText('Ferretería Alcalá')).toBeInTheDocument()
    expect(screen.getByText('Entregar por la puerta trasera')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Progreso del pedido' })).toBeInTheDocument()
  })

  it.each<[OrderStatus, string | null, boolean]>([
    ['PENDING', 'Confirmar pedido', true],
    ['CONFIRMED', 'Marcar como enviado', true],
    ['SHIPPED', 'Marcar como entregado', false],
    ['DELIVERED', null, false],
    ['CANCELLED', null, false],
  ])('estado %s: siguiente paso "%s", cancelar=%s', async (status, forward, cancel) => {
    serveOrder(makeOrder({ status }))
    renderDetail()
    await screen.findByRole('table')

    for (const label of ['Confirmar pedido', 'Marcar como enviado', 'Marcar como entregado']) {
      if (label === forward) expect(screen.getByRole('button', { name: label })).toBeInTheDocument()
      else expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument()
    }
    if (cancel) expect(screen.getByRole('button', { name: 'Cancelar pedido' })).toBeInTheDocument()
    else expect(screen.queryByRole('button', { name: 'Cancelar pedido' })).not.toBeInTheDocument()
  })

  it('avanza de estado con PATCH {status}', async () => {
    const { requests } = serveOrder(makeOrder({ status: 'PENDING' }))
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: 'Confirmar pedido' }))

    expect(await screen.findByRole('button', { name: 'Marcar como enviado' })).toBeInTheDocument()
    expect(requests.find((r) => r.method === 'PATCH')?.body).toEqual({ status: 'CONFIRMED' })
  })

  it('cancelar pide confirmación; "Mantener" no envía nada', async () => {
    const { requests } = serveOrder(makeOrder({ status: 'CONFIRMED' }))
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: 'Cancelar pedido' }))
    const dialog = await screen.findByRole('alertdialog', { name: '¿Cancelar este pedido?' })
    expect(dialog).toHaveTextContent('Se devolverán al inventario 20 unidades de 1 producto')
    await user.click(within(dialog).getByRole('button', { name: 'Mantener pedido' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(requests.some((r) => r.method === 'PATCH')).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Cancelar pedido' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancelar pedido' }))

    await waitFor(() => expect(requests.find((r) => r.method === 'PATCH')?.body).toEqual({ status: 'CANCELLED' }))
    expect(await screen.findByText(/Pedido cancelado\. El stock que reservaba volvió al inventario\./)).toBeInTheDocument()
  })

  it('transición inválida (otro usuario cambió el estado): aviso claro y recarga', async () => {
    const { requests } = serveOrder(makeOrder({ status: 'PENDING' }), () => errorResponse(409, 'INVALID_STATUS_TRANSITION'))
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: 'Confirmar pedido' }))

    expect(await screen.findByText('El pedido cambió de estado mientras tanto. Hemos actualizado la información.')).toBeInTheDocument()
    await waitFor(() => expect(requests.filter((r) => r.method === 'GET')).toHaveLength(2))
  })

  it('pedido inexistente: estado vacío con salida', async () => {
    mockFetch({})
    renderDetail()
    expect(await screen.findByText('No encontramos este pedido')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver pedidos' })).toHaveAttribute('href', '/orders')
  })
})
