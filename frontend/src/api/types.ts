/**
 * Tipos del contrato (docs/CONTRACT.md §5 y §6). Los nombres de campo son literales:
 * ids = string (UUID), fechas = ISO-8601 UTC, dinero = number con 2 decimales, cantidades = entero.
 */

export type Uuid = string
export type IsoInstant = string

// §5 Formato común ---------------------------------------------------------

/** Elemento de `details`. Validación: `{field, message}`. Stock insuficiente: `{productId, requested, available}`. */
export interface ApiErrorDetail {
  field?: string
  message?: string
  productId?: Uuid
  requested?: number
  available?: number
}

export interface ApiErrorBody {
  timestamp?: IsoInstant
  status: number
  code: string
  message?: string
  path?: string
  details?: ApiErrorDetail[]
}

export const API_ERROR_CODES = [
  'VALIDATION_ERROR',
  'INVALID_CREDENTIALS',
  'UNAUTHORIZED',
  'NOT_FOUND',
  'USERNAME_TAKEN',
  'EMAIL_TAKEN',
  'SKU_ALREADY_EXISTS',
  'INSUFFICIENT_STOCK',
  'PRODUCT_INACTIVE',
  'INVALID_STATUS_TRANSITION',
  'REGISTRATION_DISABLED',
  'SERVICE_UNAVAILABLE',
  'INTERNAL_ERROR',
] as const
export type ContractErrorCode = (typeof API_ERROR_CODES)[number]

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type SortDirection = 'asc' | 'desc'

// §6.1 auth-service ---------------------------------------------------------

export interface User {
  id: Uuid
  username: string
  email: string
}

export interface AuthResponse {
  accessToken: string
  tokenType: 'Bearer'
  /** Segundos. */
  expiresIn: number
  user: User
}

export interface RegisterRequest {
  username: string
  email: string
  password: string
}

export interface LoginRequest {
  username: string
  password: string
}

// §6.2 inventory-service ----------------------------------------------------

export interface Product {
  id: Uuid
  sku: string
  name: string
  description: string | null
  price: number
  stock: number
  lowStockThreshold: number
  active: boolean
  createdAt: IsoInstant
  updatedAt: IsoInstant
}

/** Respuesta de `GET /api/products/summary`. */
export interface InventorySummary {
  totalProducts: number
  lowStockCount: number
  totalUnits: number
}

export interface CreateProductRequest {
  sku: string
  name: string
  description?: string | null
  price: number
  stock: number
  lowStockThreshold?: number
}

/** El stock NO se cambia aquí (solo con ajustes). */
export interface UpdateProductRequest {
  sku: string
  name: string
  description?: string | null
  price: number
  lowStockThreshold: number
}

export interface StockAdjustmentRequest {
  /** Entero distinto de 0. */
  delta: number
  /** 1–200 caracteres. */
  reason: string
}

export const PRODUCT_SORT_FIELDS = ['name', 'sku', 'price', 'stock', 'createdAt'] as const
export type ProductSortField = (typeof PRODUCT_SORT_FIELDS)[number]
export type ProductSort = `${ProductSortField},${SortDirection}`

export interface ProductListParams {
  search?: string
  lowStock?: boolean
  page?: number
  size?: number
  sort?: ProductSort
}

// §6.3 order-service --------------------------------------------------------

export const ORDER_STATUSES = ['PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED'] as const
export type OrderStatus = (typeof ORDER_STATUSES)[number]

export interface OrderItem {
  id: Uuid
  productId: Uuid
  sku: string
  productName: string
  unitPrice: number
  quantity: number
  lineTotal: number
}

export interface Order {
  id: Uuid
  customerName: string
  notes: string | null
  status: OrderStatus
  total: number
  items: OrderItem[]
  createdBy: Uuid
  createdByUsername: string
  createdAt: IsoInstant
  updatedAt: IsoInstant
}

/** Elemento de `GET /api/orders` (nombre del contrato). */
export interface OrderSummary {
  id: Uuid
  customerName: string
  status: OrderStatus
  total: number
  itemCount: number
  createdByUsername: string
  createdAt: IsoInstant
}

/** Respuesta de `GET /api/orders/summary`. */
export interface OrderStatusSummary {
  total: number
  byStatus: Record<OrderStatus, number>
}

export interface CreateOrderItemRequest {
  productId: Uuid
  /** 1–10000. */
  quantity: number
}

export interface CreateOrderRequest {
  customerName: string
  notes?: string | null
  /** 1–50 ítems, sin productId repetido. */
  items: CreateOrderItemRequest[]
}

export interface UpdateOrderStatusRequest {
  status: OrderStatus
}

export const ORDER_SORT_FIELDS = ['createdAt', 'total', 'status', 'customerName'] as const
export type OrderSortField = (typeof ORDER_SORT_FIELDS)[number]
export type OrderSort = `${OrderSortField},${SortDirection}`

export interface OrderListParams {
  status?: OrderStatus
  page?: number
  size?: number
  sort?: OrderSort
}

// §6.4 notification-service -------------------------------------------------

export const NOTIFICATION_TYPES = ['NEW_ORDER', 'LOW_STOCK', 'ORDER_STATUS_CHANGED'] as const
export type NotificationType = (typeof NOTIFICATION_TYPES)[number]

export const NOTIFICATION_ENTITY_TYPES = ['ORDER', 'PRODUCT'] as const
export type NotificationEntityType = (typeof NOTIFICATION_ENTITY_TYPES)[number]

export interface Notification {
  id: Uuid
  type: NotificationType
  /** ≤ 120 caracteres. Se pinta siempre como texto. */
  title: string
  /** ≤ 500 caracteres. Se pinta siempre como texto. */
  message: string
  /** `null` ⇒ broadcast. */
  targetUserId: Uuid | null
  /** Quien provocó el evento: no se muestra toast de eventos propios. */
  actorUserId: Uuid | null
  entityType: NotificationEntityType
  entityId: Uuid
  createdAt: IsoInstant
  read: boolean
}

export interface NotificationListParams {
  unreadOnly?: boolean
  page?: number
  size?: number
}

export interface UnreadCount {
  count: number
}
