import type {
  CreateProductRequest,
  LoginRequest,
  RegisterRequest,
  StockAdjustmentRequest,
  UpdateProductRequest,
} from '../api/types'

/**
 * Validación en cliente, espejo de los límites del contrato (§6). Solo mejora la
 * experiencia: el servidor siempre tiene la última palabra.
 */

export type FieldErrors<F extends string> = Partial<Record<F, string>>
export type ValidationResult<F extends string, V> = { ok: true; value: V } | { ok: false; errors: FieldErrors<F> }

/** Entero máximo que cabe en un `int` de Java (stock, umbral, ajustes). */
export const INT_MAX = 2_147_483_647

export const LIMITS = {
  username: { min: 3, max: 32 },
  email: { max: 120 },
  password: { min: 8, max: 72 },
  sku: { min: 1, max: 40 },
  productName: { min: 1, max: 120 },
  description: { max: 1000 },
  price: { min: 0, max: 999_999.99 },
  reason: { min: 1, max: 200 },
  customerName: { min: 1, max: 120 },
  notes: { max: 500 },
  orderItems: { min: 1, max: 50 },
  quantity: { min: 1, max: 10_000 },
} as const

const USERNAME_PATTERN = /^[a-zA-Z0-9_.-]+$/
const SKU_PATTERN = /^[A-Za-z0-9._-]+$/
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const PRICE_PATTERN = /^\d{1,6}(?:[.,]\d{1,2})?$/
const INTEGER_PATTERN = /^\d+$/

function finish<F extends string, V>(errors: FieldErrors<F>, value: () => V): ValidationResult<F, V> {
  return Object.keys(errors).length > 0 ? { ok: false, errors } : { ok: true, value: value() }
}

/** "12,5" o "12.50" → 12.5. Devuelve null si no es un precio válido (0–999999.99, máx. 2 decimales). */
export function parsePrice(input: string): number | null {
  const text = input.trim()
  if (!PRICE_PATTERN.test(text)) return null
  const value = Number(text.replace(',', '.'))
  if (!Number.isFinite(value) || value < LIMITS.price.min || value > LIMITS.price.max) return null
  return Math.round(value * 100) / 100
}

/** Entero no negativo hasta INT_MAX, o null. */
export function parseNonNegativeInt(input: string): number | null {
  const text = input.trim()
  if (!INTEGER_PATTERN.test(text)) return null
  const value = Number(text)
  return Number.isSafeInteger(value) && value <= INT_MAX ? value : null
}

// Autenticación ---------------------------------------------------------------

export type LoginField = 'username' | 'password'

export function validateLogin(input: { username: string; password: string }): ValidationResult<LoginField, LoginRequest> {
  const errors: FieldErrors<LoginField> = {}
  const username = input.username.trim()
  if (!username) errors.username = 'Escribe tu nombre de usuario.'
  if (!input.password) errors.password = 'Escribe tu contraseña.'
  return finish(errors, () => ({ username, password: input.password }))
}

export type RegisterField = 'username' | 'email' | 'password' | 'confirmPassword'

export function validateRegister(input: {
  username: string
  email: string
  password: string
  confirmPassword: string
}): ValidationResult<RegisterField, RegisterRequest> {
  const errors: FieldErrors<RegisterField> = {}
  const username = input.username.trim()
  const email = input.email.trim()

  if (username.length < LIMITS.username.min || username.length > LIMITS.username.max) {
    errors.username = `Entre ${LIMITS.username.min} y ${LIMITS.username.max} caracteres.`
  } else if (!USERNAME_PATTERN.test(username)) {
    errors.username = 'Solo letras sin tilde, números y los signos _ . -'
  }

  if (!email) errors.email = 'Escribe tu correo.'
  else if (email.length > LIMITS.email.max) errors.email = `Máximo ${LIMITS.email.max} caracteres.`
  else if (!EMAIL_PATTERN.test(email)) errors.email = 'Escribe un correo válido, como nombre@empresa.com.'

  if (input.password.length < LIMITS.password.min) {
    errors.password = `Al menos ${LIMITS.password.min} caracteres.`
  } else if (input.password.length > LIMITS.password.max) {
    errors.password = `Máximo ${LIMITS.password.max} caracteres.`
  }

  if (!input.confirmPassword) errors.confirmPassword = 'Repite la contraseña.'
  else if (input.confirmPassword !== input.password) errors.confirmPassword = 'Las contraseñas no coinciden.'

  return finish(errors, () => ({ username, email, password: input.password }))
}

// Productos ---------------------------------------------------------------------

export type ProductField = 'sku' | 'name' | 'description' | 'price' | 'stock' | 'lowStockThreshold'

export interface ProductFormValues {
  sku: string
  name: string
  description: string
  price: string
  /** Solo al crear. */
  stock: string
  lowStockThreshold: string
}

export const EMPTY_PRODUCT_FORM: ProductFormValues = {
  sku: '',
  name: '',
  description: '',
  price: '',
  stock: '0',
  lowStockThreshold: '5',
}

interface ProductCommon {
  sku: string
  name: string
  description: string | null
  price: number
  lowStockThreshold: number
}

function validateProductCommon(values: ProductFormValues, errors: FieldErrors<ProductField>): ProductCommon {
  const sku = values.sku.trim().toUpperCase()
  if (sku.length < LIMITS.sku.min) errors.sku = 'Escribe el SKU.'
  else if (sku.length > LIMITS.sku.max) errors.sku = `Máximo ${LIMITS.sku.max} caracteres.`
  else if (!SKU_PATTERN.test(sku)) errors.sku = 'Solo letras sin tilde, números y los signos . _ -'

  const name = values.name.trim()
  if (name.length < LIMITS.productName.min) errors.name = 'Escribe el nombre.'
  else if (name.length > LIMITS.productName.max) errors.name = `Máximo ${LIMITS.productName.max} caracteres.`

  const description = values.description.trim()
  if (description.length > LIMITS.description.max) errors.description = `Máximo ${LIMITS.description.max} caracteres.`

  const price = parsePrice(values.price)
  if (price === null) errors.price = 'Precio entre 0 y 999999,99 con hasta 2 decimales (p. ej. 12,50).'

  const threshold = parseNonNegativeInt(values.lowStockThreshold)
  if (threshold === null) errors.lowStockThreshold = 'Número entero, 0 o mayor.'

  return { sku, name, description: description || null, price: price ?? 0, lowStockThreshold: threshold ?? 0 }
}

export function validateCreateProduct(values: ProductFormValues): ValidationResult<ProductField, CreateProductRequest> {
  const errors: FieldErrors<ProductField> = {}
  const common = validateProductCommon(values, errors)
  const stock = parseNonNegativeInt(values.stock)
  if (stock === null) errors.stock = 'Número entero, 0 o mayor.'
  return finish(errors, () => ({ ...common, stock: stock ?? 0 }))
}

export function validateUpdateProduct(values: ProductFormValues): ValidationResult<ProductField, UpdateProductRequest> {
  const errors: FieldErrors<ProductField> = {}
  const common = validateProductCommon(values, errors)
  return finish(errors, () => common)
}

// Ajuste de stock ---------------------------------------------------------------

export type StockDirection = 'in' | 'out'
export type StockAdjustmentField = 'quantity' | 'reason'

export function validateStockAdjustment(
  input: { direction: StockDirection; quantity: string; reason: string },
  currentStock: number,
): ValidationResult<StockAdjustmentField, StockAdjustmentRequest> {
  const errors: FieldErrors<StockAdjustmentField> = {}
  const quantity = parseNonNegativeInt(input.quantity)
  if (quantity === null || quantity < 1) {
    errors.quantity = 'Número entero mayor que 0.'
  } else if (input.direction === 'out' && quantity > currentStock) {
    errors.quantity = `No puedes retirar más de lo que hay (${currentStock}).`
  } else if (input.direction === 'in' && currentStock + quantity > INT_MAX) {
    errors.quantity = 'La cantidad es demasiado grande.'
  }

  const reason = input.reason.trim()
  if (reason.length < LIMITS.reason.min) errors.reason = 'Explica el motivo del ajuste.'
  else if (reason.length > LIMITS.reason.max) errors.reason = `Máximo ${LIMITS.reason.max} caracteres.`

  return finish(errors, () => ({ delta: input.direction === 'in' ? (quantity ?? 0) : -(quantity ?? 0), reason }))
}
