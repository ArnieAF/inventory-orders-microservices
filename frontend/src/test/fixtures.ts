import type { Order, Product } from '../api/types'

export function makeProduct(overrides: Partial<Product> = {}): Product {
  return {
    id: 'aaaaaaaa-0000-0000-0000-000000000001',
    sku: 'CAJ-0042',
    name: 'Caja de cartón doble canal 60x40',
    description: 'Caja para envíos grandes',
    price: 2.35,
    stock: 340,
    lowStockThreshold: 50,
    active: true,
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-11-01T09:00:00Z',
    ...overrides,
  }
}

export function makeOrder(overrides: Partial<Order> = {}): Order {
  return {
    id: '3f2a9c1b-7d4e-4a1b-9c2d-123456789abc',
    customerName: 'Ferretería Alcalá',
    notes: 'Entregar por la puerta trasera',
    status: 'PENDING',
    total: 47,
    items: [
      {
        id: 'item-1',
        productId: 'aaaaaaaa-0000-0000-0000-000000000001',
        sku: 'CAJ-0042',
        productName: 'Caja de cartón doble canal 60x40',
        unitPrice: 2.35,
        quantity: 20,
        lineTotal: 47,
      },
    ],
    createdBy: '11111111-1111-1111-1111-111111111111',
    createdByUsername: 'marta.ops',
    createdAt: '2026-11-05T10:00:00Z',
    updatedAt: '2026-11-05T10:00:00Z',
    ...overrides,
  }
}
