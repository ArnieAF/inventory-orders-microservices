import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { makeOrder, makeProduct } from '../../test/fixtures'
import { errorResponse, jsonResponse, mockFetch, page, renderRoutes, signInTestUser } from '../../test/utils'
import { NewOrderPage } from './NewOrderPage'

const caja = makeProduct({ id: 'p1', name: 'Caja grande', sku: 'CAJ-1', price: 2.5, stock: 10 })
const cinta = makeProduct({ id: 'p2', name: 'Cinta adhesiva', sku: 'CIN-1', price: 1.2, stock: 4 })

function renderNewOrder() {
  return renderRoutes(
    [
      { path: '/orders/new', element: <NewOrderPage /> },
      { path: '/orders/:id', element: <p>Detalle del pedido creado</p> },
    ],
    '/orders/new',
  )
}

describe('NewOrderPage', () => {
  beforeEach(() => signInTestUser())

  it('añadir el mismo producto dos veces suma cantidad; el total se estima con los precios mostrados', async () => {
    mockFetch({ 'GET /api/products': () => jsonResponse(page([caja, cinta])) })
    const user = userEvent.setup()
    renderNewOrder()

    await user.click(await screen.findByRole('button', { name: 'Añadir Caja grande' }))
    await user.click(screen.getByRole('button', { name: 'Añadir otra unidad de Caja grande' }))
    await user.click(screen.getByRole('button', { name: 'Añadir Cinta adhesiva' }))

    const lines = screen.getByRole('list', { name: 'Líneas del pedido' })
    expect(within(lines).getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByLabelText('Cantidad de Caja grande')).toHaveValue('2')
    // 2 × 2,50 + 1 × 1,20
    expect(screen.getByText('6,20')).toBeInTheDocument()
  })

  it('no envía si faltan cliente o productos', async () => {
    const { requests } = mockFetch({ 'GET /api/products': () => jsonResponse(page([caja])) })
    const user = userEvent.setup()
    renderNewOrder()
    await screen.findByRole('button', { name: 'Añadir Caja grande' })

    await user.click(screen.getByRole('button', { name: 'Crear pedido' }))

    expect(await screen.findByText('Escribe el nombre del cliente.')).toBeInTheDocument()
    expect(screen.getByText('Añade al menos un producto.')).toBeInTheDocument()
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })

  it('INSUFFICIENT_STOCK: marca qué falta con details y permite ajustar a lo disponible', async () => {
    let attempt = 0
    const { requests } = mockFetch({
      'GET /api/products': () => jsonResponse(page([caja, cinta])),
      'POST /api/orders': () => {
        attempt += 1
        return attempt === 1
          ? errorResponse(409, 'INSUFFICIENT_STOCK', [{ productId: 'p2', requested: 6, available: 2 }])
          : jsonResponse(makeOrder({ id: 'new-order' }), 201)
      },
    })
    const user = userEvent.setup()
    renderNewOrder()

    await user.click(await screen.findByRole('button', { name: 'Añadir Caja grande' }))
    await user.click(screen.getByRole('button', { name: 'Añadir Cinta adhesiva' }))
    const quantity = screen.getByLabelText('Cantidad de Cinta adhesiva')
    await user.clear(quantity)
    await user.type(quantity, '6')
    await user.type(screen.getByLabelText('Cliente'), 'Ferretería Alcalá')
    await user.click(screen.getByRole('button', { name: 'Crear pedido' }))

    expect(await screen.findByText('No hay stock suficiente')).toBeInTheDocument()
    expect(screen.getByText('Pediste 6, disponibles 2.')).toBeInTheDocument()
    expect(requests.find((r) => r.method === 'POST')?.body).toEqual({
      customerName: 'Ferretería Alcalá',
      notes: null,
      items: [
        { productId: 'p1', quantity: 1 },
        { productId: 'p2', quantity: 6 },
      ],
    })

    await user.click(screen.getByRole('button', { name: 'Ajustar cantidades' }))
    expect(screen.getByLabelText('Cantidad de Cinta adhesiva')).toHaveValue('2')
    expect(screen.queryByText('No hay stock suficiente')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Crear pedido' }))
    expect(await screen.findByText('Detalle del pedido creado')).toBeInTheDocument()
    await waitFor(() => expect(attempt).toBe(2))
  })

  it('producto dado de baja entre medias: aviso específico', async () => {
    mockFetch({
      'GET /api/products': () => jsonResponse(page([caja])),
      'POST /api/orders': () => errorResponse(409, 'PRODUCT_INACTIVE', [{ productId: 'p1' }]),
    })
    const user = userEvent.setup()
    renderNewOrder()
    await user.click(await screen.findByRole('button', { name: 'Añadir Caja grande' }))
    await user.type(screen.getByLabelText('Cliente'), 'Cliente')
    await user.click(screen.getByRole('button', { name: 'Crear pedido' }))

    expect(
      await screen.findByText('Alguno de los productos se dio de baja. Quita los marcados y vuelve a intentarlo.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Ya no está disponible: quítalo del pedido.')).toBeInTheDocument()
  })
})
