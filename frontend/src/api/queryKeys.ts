import type { OrderListParams, ProductListParams } from './types'

export const queryKeys = {
  products: {
    all: ['products'] as const,
    list: (params: ProductListParams) => ['products', 'list', params] as const,
    picker: (search: string) => ['products', 'picker', search] as const,
    summary: ['products', 'summary'] as const,
    detail: (id: string) => ['products', 'detail', id] as const,
  },
  orders: {
    all: ['orders'] as const,
    list: (params: OrderListParams) => ['orders', 'list', params] as const,
    summary: ['orders', 'summary'] as const,
    detail: (id: string) => ['orders', 'detail', id] as const,
  },
  notifications: {
    all: ['notifications'] as const,
    list: (unreadOnly: boolean) => ['notifications', 'list', { unreadOnly }] as const,
    unreadCount: ['notifications', 'unread-count'] as const,
  },
}
