import { describe, expect, it } from 'vitest'
import { makeProduct } from '../test/fixtures'
import {
  addProductToDraft,
  applyShortages,
  estimateTotalCents,
  setLineQuantity,
  shortagesFromDetails,
  stepLineQuantity,
  validateOrderDraft,
  type DraftLine,
} from './orderDraft'

const caja = makeProduct({ id: 'p1', price: 2.35, stock: 10 })
const cinta = makeProduct({ id: 'p2', sku: 'CIN-48', name: 'Cinta adhesiva 48 mm', price: 0.1, stock: 500 })

function line(overrides: Partial<DraftLine> = {}): DraftLine {
  return { productId: 'p1', sku: 'CAJ-0042', name: 'Caja', unitPrice: 2.35, stock: 10, quantity: '1', ...overrides }
}

describe('borrador de pedido', () => {
  it('añadir dos veces el mismo producto suma cantidad en vez de duplicar la línea', () => {
    let result = addProductToDraft([], caja)
    expect(result.status).toBe('added')
    result = addProductToDraft(result.lines, caja)
    expect(result.status).toBe('incremented')
    expect(result.lines).toHaveLength(1)
    expect(result.lines[0]?.quantity).toBe('2')
  })

  it('no supera 50 productos distintos', () => {
    const lines = Array.from({ length: 50 }, (_, i) => line({ productId: `p${i}` }))
    const result = addProductToDraft(lines, makeProduct({ id: 'nuevo' }))
    expect(result.status).toBe('max-lines')
    expect(result.lines).toHaveLength(50)
  })

  it('no supera 10000 unidades por línea', () => {
    const result = addProductToDraft([line({ quantity: '10000' })], caja)
    expect(result.status).toBe('limit')
    expect(stepLineQuantity([line({ quantity: '10000' })], 'p1', 1)[0]?.quantity).toBe('10000')
    expect(stepLineQuantity([line({ quantity: '1' })], 'p1', -1)[0]?.quantity).toBe('1')
  })

  it('el campo de cantidad solo admite dígitos', () => {
    expect(setLineQuantity([line()], 'p1', '1a2-')[0]?.quantity).toBe('12')
  })

  it('calcula el total estimado en céntimos sin errores de coma flotante', () => {
    const lines = [line({ unitPrice: 0.1, quantity: '3' }), line({ productId: 'p2', unitPrice: 0.2, quantity: '1' })]
    expect(estimateTotalCents(lines)).toBe(50)
    expect(estimateTotalCents([line({ unitPrice: 999999.99, quantity: '10000' })])).toBe(99_999_999 * 10_000)
  })

  it('valida cliente, notas, número de líneas, cantidades y duplicados', () => {
    const empty = validateOrderDraft({ customerName: '  ', notes: 'x'.repeat(501), lines: [] })
    expect(empty.ok).toBe(false)
    if (empty.ok) return
    expect(empty.errors.customerName).toBeDefined()
    expect(empty.errors.notes).toBeDefined()
    expect(empty.errors.items).toBe('Añade al menos un producto.')

    const badQuantities = validateOrderDraft({
      customerName: 'Ferretería Alcalá',
      notes: '',
      lines: [line({ quantity: '0' }), line({ productId: 'p2', quantity: '10001' })],
    })
    expect(badQuantities.ok).toBe(false)
    if (!badQuantities.ok) expect(Object.keys(badQuantities.errors.quantities)).toEqual(['p1', 'p2'])

    const duplicated = validateOrderDraft({ customerName: 'X', notes: '', lines: [line(), line()] })
    expect(duplicated.ok).toBe(false)
    if (!duplicated.ok) expect(duplicated.errors.items).toMatch(/repetidos/)
  })

  it('genera la petición del contrato (sin precios: los pone el servidor)', () => {
    const result = validateOrderDraft({
      customerName: '  Ferretería Alcalá ',
      notes: '',
      lines: [line({ quantity: '3' }), line({ productId: 'p2', quantity: '12' })],
    })
    expect(result).toEqual({
      ok: true,
      request: {
        customerName: 'Ferretería Alcalá',
        notes: null,
        items: [
          { productId: 'p1', quantity: 3 },
          { productId: 'p2', quantity: 12 },
        ],
      },
    })
  })

  it('INSUFFICIENT_STOCK: lee details y ajusta a lo disponible (quita lo agotado)', () => {
    const shortages = shortagesFromDetails([
      { productId: 'p1', requested: 8, available: 3 },
      { productId: 'p2', requested: 5, available: 0 },
      { field: 'ignored' },
    ])
    expect(shortages.get('p1')).toEqual({ requested: 8, available: 3 })
    const adjusted = applyShortages(
      [line({ quantity: '8' }), line({ productId: 'p2', quantity: '5' }), line({ productId: 'p3', quantity: '1' })],
      shortages,
    )
    expect(adjusted.map((l) => [l.productId, l.quantity])).toEqual([
      ['p1', '3'],
      ['p3', '1'],
    ])
    expect(cinta.stock).toBe(500)
  })
})
