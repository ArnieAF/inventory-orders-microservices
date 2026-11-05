import { describe, expect, it } from 'vitest'
import {
  EMPTY_PRODUCT_FORM,
  INT_MAX,
  parsePrice,
  validateCreateProduct,
  validateRegister,
  validateStockAdjustment,
  validateUpdateProduct,
} from './validation'
import { formatBadgeCount, formatInteger, formatMoney, shortId } from './format'

describe('parsePrice', () => {
  it('acepta coma o punto y hasta 2 decimales dentro de 0–999999.99', () => {
    expect(parsePrice('12,5')).toBe(12.5)
    expect(parsePrice('0')).toBe(0)
    expect(parsePrice('999999.99')).toBe(999999.99)
    expect(parsePrice('1.234,56')).toBeNull()
    expect(parsePrice('1000000')).toBeNull()
    expect(parsePrice('1,234')).toBeNull()
    expect(parsePrice('-1')).toBeNull()
    expect(parsePrice('abc')).toBeNull()
  })
})

describe('producto', () => {
  it('normaliza SKU a mayúsculas y valida límites del contrato', () => {
    const ok = validateCreateProduct({ ...EMPTY_PRODUCT_FORM, sku: 'caj-0042', name: ' Caja ', price: '2,35', stock: '10' })
    expect(ok).toEqual({
      ok: true,
      value: { sku: 'CAJ-0042', name: 'Caja', description: null, price: 2.35, stock: 10, lowStockThreshold: 5 },
    })

    const bad = validateCreateProduct({
      sku: 'CAJA 42!',
      name: 'x'.repeat(121),
      description: 'x'.repeat(1001),
      price: '1000000',
      stock: '-3',
      lowStockThreshold: '1.5',
    })
    expect(bad.ok).toBe(false)
    if (!bad.ok) expect(Object.keys(bad.errors).sort()).toEqual(['description', 'lowStockThreshold', 'name', 'price', 'sku', 'stock'])
  })

  it('al editar no se envía stock (contrato: el stock no se cambia en PUT)', () => {
    const result = validateUpdateProduct({ ...EMPTY_PRODUCT_FORM, sku: 'A', name: 'B', price: '1', stock: 'no importa' })
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.value).not.toHaveProperty('stock')
  })
})

describe('ajuste de stock', () => {
  it('convierte entrada/salida en delta con signo y exige motivo', () => {
    expect(validateStockAdjustment({ direction: 'out', quantity: '3', reason: 'Rotura' }, 10)).toEqual({
      ok: true,
      value: { delta: -3, reason: 'Rotura' },
    })
    const result = validateStockAdjustment({ direction: 'out', quantity: '11', reason: ' ' }, 10)
    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.errors.quantity).toMatch(/más de lo que hay/)
      expect(result.errors.reason).toBeDefined()
    }
    expect(validateStockAdjustment({ direction: 'in', quantity: '0', reason: 'x' }, 0).ok).toBe(false)
    expect(validateStockAdjustment({ direction: 'in', quantity: String(INT_MAX), reason: 'x' }, 1).ok).toBe(false)
  })
})

describe('registro', () => {
  it('aplica los límites de usuario, correo y contraseña', () => {
    const result = validateRegister({ username: 'ab', email: 'no-es-correo', password: 'corta', confirmPassword: 'otra' })
    expect(result.ok).toBe(false)
    if (!result.ok) expect(Object.keys(result.errors).sort()).toEqual(['confirmPassword', 'email', 'password', 'username'])
    expect(
      validateRegister({ username: 'marta.ops', email: 'marta@almacen.test', password: '12345678', confirmPassword: '12345678' })
        .ok,
    ).toBe(true)
    expect(validateRegister({ username: 'josé', email: 'a@b.co', password: '12345678', confirmPassword: '12345678' }).ok).toBe(
      false,
    )
  })
})

describe('formato', () => {
  it('agrupa miles siempre y usa 2 decimales', () => {
    expect(formatInteger(1234)).toBe('1.234')
    expect(formatMoney(499999995000)).toBe('499.999.995.000,00')
    expect(formatMoney(Number.NaN)).toBe('-')
  })

  it('contador 99+ y referencia corta', () => {
    expect(formatBadgeCount(7)).toBe('7')
    expect(formatBadgeCount(100)).toBe('99+')
    expect(shortId('3f2a9c1b-7d4e-4a1b-9c2d-123456789abc')).toBe('3F2A9C1B')
  })
})
