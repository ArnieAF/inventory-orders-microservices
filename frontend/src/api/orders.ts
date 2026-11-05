import { api } from './http'
import type {
  CreateOrderRequest,
  Order,
  OrderListParams,
  OrderStatus,
  OrderStatusSummary,
  OrderSummary,
  Page,
  UpdateOrderStatusRequest,
} from './types'

const id = (value: string) => encodeURIComponent(value)

export function listOrders(params: OrderListParams, signal?: AbortSignal): Promise<Page<OrderSummary>> {
  return api.request<Page<OrderSummary>>('/api/orders', {
    query: { status: params.status, page: params.page, size: params.size, sort: params.sort },
    ...(signal ? { signal } : {}),
  })
}

export function fetchOrderStatusSummary(signal?: AbortSignal): Promise<OrderStatusSummary> {
  return api.request<OrderStatusSummary>('/api/orders/summary', signal ? { signal } : {})
}

export function fetchOrder(orderId: string, signal?: AbortSignal): Promise<Order> {
  return api.request<Order>(`/api/orders/${id(orderId)}`, signal ? { signal } : {})
}

export function createOrder(body: CreateOrderRequest): Promise<Order> {
  return api.request<Order>('/api/orders', { method: 'POST', body })
}

export function updateOrderStatus(orderId: string, status: OrderStatus): Promise<Order> {
  const body: UpdateOrderStatusRequest = { status }
  return api.request<Order>(`/api/orders/${id(orderId)}/status`, { method: 'PATCH', body })
}
