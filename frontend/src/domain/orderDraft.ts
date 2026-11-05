import type { ApiErrorDetail, CreateOrderRequest, Product } from '../api/types'
import { toCents } from './format'
import { LIMITS } from './validation'

/** Línea del borrador. Precio y stock son los MOSTRADOS: el servidor recalcula el total al crear. */
export interface DraftLine {
  productId: string
  sku: string
  name: string
  unitPrice: number
  /** Stock conocido al añadir (puede estar desactualizado). */
  stock: number
  /** Texto del campo, para no pelear con lo que escribe el usuario. */
  quantity: string
}

export type AddLineResult = { lines: DraftLine[]; status: 'added' | 'incremented' | 'limit' | 'max-lines' }

/** Añadir un producto que ya está en el pedido suma 1 a su línea: nunca se duplica un productId. */
export function addProductToDraft(lines: readonly DraftLine[], product: Product): AddLineResult {
  const existing = lines.find((line) => line.productId === product.id)
  if (existing) {
    const current = parseQuantity(existing.quantity) ?? 0
    if (current >= LIMITS.quantity.max) return { lines: [...lines], status: 'limit' }
    return {
      lines: lines.map((line) =>
        line.productId === product.id ? { ...line, quantity: String(current + 1), stock: product.stock } : line,
      ),
      status: 'incremented',
    }
  }
  if (lines.length >= LIMITS.orderItems.max) return { lines: [...lines], status: 'max-lines' }
  return {
    lines: [
      ...lines,
      {
        productId: product.id,
        sku: product.sku,
        name: product.name,
        unitPrice: product.price,
        stock: product.stock,
        quantity: '1',
      },
    ],
    status: 'added',
  }
}

export function setLineQuantity(lines: readonly DraftLine[], productId: string, quantity: string): DraftLine[] {
  const clean = quantity.replace(/[^\d]/g, '').slice(0, 6)
  return lines.map((line) => (line.productId === productId ? { ...line, quantity: clean } : line))
}

export function stepLineQuantity(lines: readonly DraftLine[], productId: string, step: 1 | -1): DraftLine[] {
  return lines.map((line) => {
    if (line.productId !== productId) return line
    const current = parseQuantity(line.quantity) ?? 0
    const next = Math.min(LIMITS.quantity.max, Math.max(LIMITS.quantity.min, current + step))
    return { ...line, quantity: String(next) }
  })
}

export function removeLine(lines: readonly DraftLine[], productId: string): DraftLine[] {
  return lines.filter((line) => line.productId !== productId)
}

/** Entero 1–10000 o null. */
export function parseQuantity(text: string): number | null {
  if (!/^\d+$/.test(text.trim())) return null
  const value = Number(text)
  return Number.isSafeInteger(value) && value >= LIMITS.quantity.min && value <= LIMITS.quantity.max ? value : null
}

/** Total estimado en céntimos con los precios mostrados (las cantidades no válidas no suman). */
export function estimateTotalCents(lines: readonly DraftLine[]): number {
  return lines.reduce((sum, line) => sum + toCents(line.unitPrice) * (parseQuantity(line.quantity) ?? 0), 0)
}

export interface DraftErrors {
  customerName?: string
  notes?: string
  items?: string
  quantities: Record<string, string>
}

export type DraftValidation = { ok: true; request: CreateOrderRequest } | { ok: false; errors: DraftErrors }

export function validateOrderDraft(input: {
  customerName: string
  notes: string
  lines: readonly DraftLine[]
}): DraftValidation {
  const errors: DraftErrors = { quantities: {} }
  const customerName = input.customerName.trim()
  const notes = input.notes.trim()

  if (customerName.length < LIMITS.customerName.min) errors.customerName = 'Escribe el nombre del cliente.'
  else if (customerName.length > LIMITS.customerName.max)
    errors.customerName = `Máximo ${LIMITS.customerName.max} caracteres.`

  if (notes.length > LIMITS.notes.max) errors.notes = `Máximo ${LIMITS.notes.max} caracteres.`

  if (input.lines.length < LIMITS.orderItems.min) errors.items = 'Añade al menos un producto.'
  else if (input.lines.length > LIMITS.orderItems.max)
    errors.items = `Un pedido admite como máximo ${LIMITS.orderItems.max} productos distintos.`

  const seen = new Set<string>()
  for (const line of input.lines) {
    if (seen.has(line.productId)) errors.items = 'Hay productos repetidos. Junta las cantidades en una sola línea.'
    seen.add(line.productId)
    if (parseQuantity(line.quantity) === null) {
      errors.quantities[line.productId] = `Cantidad entre ${LIMITS.quantity.min} y ${LIMITS.quantity.max}.`
    }
  }

  const hasErrors =
    errors.customerName !== undefined ||
    errors.notes !== undefined ||
    errors.items !== undefined ||
    Object.keys(errors.quantities).length > 0
  if (hasErrors) return { ok: false, errors }

  return {
    ok: true,
    request: {
      customerName,
      notes: notes || null,
      items: input.lines.map((line) => ({ productId: line.productId, quantity: parseQuantity(line.quantity) ?? 1 })),
    },
  }
}

export interface StockShortage {
  requested: number
  available: number
}

/** `details` de INSUFFICIENT_STOCK (`{productId, requested, available}`) → mapa por producto. */
export function shortagesFromDetails(details: readonly ApiErrorDetail[]): Map<string, StockShortage> {
  const result = new Map<string, StockShortage>()
  for (const detail of details) {
    if (typeof detail.productId !== 'string') continue
    result.set(detail.productId, {
      requested: typeof detail.requested === 'number' ? detail.requested : 0,
      available: typeof detail.available === 'number' ? Math.max(0, detail.available) : 0,
    })
  }
  return result
}

/** Ajusta cada línea con faltante a lo disponible; quita las que no tienen nada disponible. */
export function applyShortages(lines: readonly DraftLine[], shortages: ReadonlyMap<string, StockShortage>): DraftLine[] {
  return lines.flatMap((line) => {
    const shortage = shortages.get(line.productId)
    if (!shortage) return [line]
    if (shortage.available <= 0) return []
    const available = Math.min(shortage.available, LIMITS.quantity.max)
    return [{ ...line, quantity: String(available), stock: shortage.available }]
  })
}
