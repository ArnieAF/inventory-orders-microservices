import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { makeProduct } from '../../test/fixtures'
import { errorResponse, jsonResponse, mockFetch, page, renderPage, signInTestUser } from '../../test/utils'
import { ProductsPage } from './ProductsPage'

const LONG_NAME = 'Palé europeo reforzado de madera tratada térmicamente '.repeat(4).trim()

const products = [
  makeProduct({ id: 'p1', sku: 'CAJ-0042', name: 'Caja de cartón doble canal 60x40', stock: 340, lowStockThreshold: 50 }),
  makeProduct({ id: 'p2', sku: 'CINTA-ADHESIVA-TRANSPARENTE-48MM-X-132M', name: 'Cinta adhesiva', stock: 3, lowStockThreshold: 10 }),
  makeProduct({ id: 'p3', sku: 'PAL-EUR', name: LONG_NAME, price: 999999.99, stock: 0, lowStockThreshold: 0 }),
]

function renderProducts(initialEntry = '/products') {
  return renderPage(<ProductsPage />, { path: '/products', initialEntry })
}

describe('ProductsPage', () => {
  beforeEach(() => signInTestUser())

  it('lista productos con precio, stock y aviso de stock bajo', async () => {
    mockFetch({ 'GET /api/products': () => jsonResponse(page(products, { totalElements: 3 })) })
    renderProducts()

    const table = await screen.findByRole('table', { name: 'Productos del catálogo' })
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(4)
    expect(within(table).getByText('Caja de cartón doble canal 60x40')).toBeInTheDocument()
    expect(within(table).getByText('999.999,99')).toBeInTheDocument()
    expect(within(table).getByText('Stock bajo')).toBeInTheDocument()
    expect(within(table).getByText('Agotado')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: `Editar ${LONG_NAME}` })).toBeInTheDocument()
    expect(screen.getByText(/3 productos/)).toBeInTheDocument()
  })

  it('pide la primera página ordenada por nombre y aplica el filtro de la URL', async () => {
    const { requests } = mockFetch({ 'GET /api/products': () => jsonResponse(page([products[1]])) })
    renderProducts('/products?lowStock=true')

    await screen.findByText('Cinta adhesiva')
    const params = requests[0]?.url.searchParams
    expect(params?.get('lowStock')).toBe('true')
    expect(params?.get('page')).toBe('0')
    expect(params?.get('size')).toBe('20')
    expect(params?.get('sort')).toBe('name,asc')
    expect(screen.getByRole('button', { name: 'Solo stock bajo' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('busca con debounce y muestra un vacío útil cuando no hay resultados', async () => {
    const { requests } = mockFetch({
      'GET /api/products': ({ url }) =>
        jsonResponse(url.searchParams.get('search') ? page([]) : page(products)),
    })
    const user = userEvent.setup()
    renderProducts()
    await screen.findByText('Cinta adhesiva')

    await user.type(screen.getByLabelText('Buscar por SKU o nombre'), 'zzz')

    expect(await screen.findByText('Nada coincide con «zzz»')).toBeInTheDocument()
    const searches = requests.filter((r) => r.url.searchParams.has('search'))
    expect(searches).toHaveLength(1)
    expect(searches[0]?.url.searchParams.get('search')).toBe('zzz')

    await user.click(screen.getByRole('button', { name: 'Quitar filtros' }))
    expect(await screen.findByText('Cinta adhesiva')).toBeInTheDocument()
  })

  it('catálogo vacío invita a crear el primer producto', async () => {
    mockFetch({ 'GET /api/products': () => jsonResponse(page([])) })
    renderProducts()
    expect(await screen.findByText('Aún no hay productos')).toBeInTheDocument()
  })

  it('error del servidor: mensaje en español y reintento, nunca el texto crudo', async () => {
    let fail = true
    mockFetch({
      'GET /api/products': () => (fail ? errorResponse(503, 'SERVICE_UNAVAILABLE') : jsonResponse(page(products))),
    })
    const user = userEvent.setup()
    renderProducts()

    expect(
      await screen.findByText('El servicio no está disponible ahora mismo. Inténtalo de nuevo en unos segundos.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/RAW SERVER MESSAGE/)).not.toBeInTheDocument()

    fail = false
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Cinta adhesiva')).toBeInTheDocument()
  })

  it('crear producto: valida, envía el contrato y muestra el SKU duplicado en su campo', async () => {
    const { requests } = mockFetch({
      'GET /api/products': () => jsonResponse(page(products)),
      'POST /api/products': () => errorResponse(409, 'SKU_ALREADY_EXISTS'),
    })
    const user = userEvent.setup()
    renderProducts()
    await screen.findByText('Cinta adhesiva')

    await user.click(screen.getByRole('button', { name: 'Nuevo producto' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nuevo producto' })
    await user.click(within(dialog).getByRole('button', { name: 'Crear producto' }))
    expect(await within(dialog).findByText('Escribe el SKU.')).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('SKU'), 'caj-0042')
    await user.type(within(dialog).getByLabelText('Precio'), '2,35')
    await user.type(within(dialog).getByLabelText(/^Nombre/), 'Caja nueva')
    await user.click(within(dialog).getByRole('button', { name: 'Crear producto' }))

    expect(await within(dialog).findByText('Ya existe un producto con ese SKU.')).toBeInTheDocument()
    const post = requests.find((r) => r.method === 'POST')
    expect(post?.body).toEqual({ sku: 'CAJ-0042', name: 'Caja nueva', description: null, price: 2.35, stock: 0, lowStockThreshold: 5 })
  })

  it('dar de baja pide confirmación y llama a DELETE', async () => {
    const { requests } = mockFetch({
      'GET /api/products': () => jsonResponse(page(products)),
      'DELETE /api/products/p2': () => new Response(null, { status: 204 }),
    })
    const user = userEvent.setup()
    renderProducts()
    await screen.findByText('Cinta adhesiva')

    await user.click(screen.getByRole('button', { name: 'Dar de baja Cinta adhesiva' }))
    const dialog = await screen.findByRole('alertdialog', { name: '¿Dar de baja este producto?' })
    expect(requests.some((r) => r.method === 'DELETE')).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: 'Dar de baja' }))

    await waitFor(() => expect(requests.some((r) => r.method === 'DELETE' && r.url.pathname === '/api/products/p2')).toBe(true))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
  })

  it('ajuste de stock: salida con motivo envía delta negativo', async () => {
    const { requests } = mockFetch({
      'GET /api/products': () => jsonResponse(page(products)),
      'POST /api/products/p1/stock-adjustments': () => jsonResponse({ ...products[0], stock: 330 }),
    })
    const user = userEvent.setup()
    renderProducts()
    await screen.findByText('Cinta adhesiva')

    await user.click(screen.getByRole('button', { name: 'Ajustar stock de Caja de cartón doble canal 60x40' }))
    const dialog = await screen.findByRole('dialog', { name: 'Ajustar stock' })
    await user.click(within(dialog).getByLabelText('Salida'))
    await user.type(within(dialog).getByLabelText('Unidades que salen'), '10')
    await user.type(within(dialog).getByLabelText(/^Motivo/), 'Rotura en almacén')
    expect(within(dialog).getByText('330')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Registrar ajuste' }))

    await waitFor(() =>
      expect(requests.find((r) => r.method === 'POST')?.body).toEqual({ delta: -10, reason: 'Rotura en almacén' }),
    )
  })
})
