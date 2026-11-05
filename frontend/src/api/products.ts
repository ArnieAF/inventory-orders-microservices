import { api } from './http'
import type {
  CreateProductRequest,
  InventorySummary,
  Page,
  Product,
  ProductListParams,
  StockAdjustmentRequest,
  UpdateProductRequest,
} from './types'

const id = (value: string) => encodeURIComponent(value)

export function listProducts(params: ProductListParams, signal?: AbortSignal): Promise<Page<Product>> {
  return api.request<Page<Product>>('/api/products', {
    query: {
      search: params.search?.trim() || undefined,
      lowStock: params.lowStock ? true : undefined,
      page: params.page,
      size: params.size,
      sort: params.sort,
    },
    ...(signal ? { signal } : {}),
  })
}

export function fetchInventorySummary(signal?: AbortSignal): Promise<InventorySummary> {
  return api.request<InventorySummary>('/api/products/summary', signal ? { signal } : {})
}

export function fetchProduct(productId: string, signal?: AbortSignal): Promise<Product> {
  return api.request<Product>(`/api/products/${id(productId)}`, signal ? { signal } : {})
}

export function createProduct(body: CreateProductRequest): Promise<Product> {
  return api.request<Product>('/api/products', { method: 'POST', body })
}

export function updateProduct(productId: string, body: UpdateProductRequest): Promise<Product> {
  return api.request<Product>(`/api/products/${id(productId)}`, { method: 'PUT', body })
}

export function adjustStock(productId: string, body: StockAdjustmentRequest): Promise<Product> {
  return api.request<Product>(`/api/products/${id(productId)}/stock-adjustments`, { method: 'POST', body })
}

export function deleteProduct(productId: string): Promise<void> {
  return api.request<void>(`/api/products/${id(productId)}`, { method: 'DELETE' })
}
