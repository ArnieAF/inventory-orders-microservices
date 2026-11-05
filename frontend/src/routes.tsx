import type { RouteObject } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { AppShell } from './layout/AppShell'

/**
 * Cada pantalla se carga en su propio fragmento (React Router `lazy`): el login no
 * descarga el código de pedidos ni de productos.
 */
export const routes: RouteObject[] = [
  {
    path: '/login',
    lazy: async () => {
      const { LoginPage } = await import('./pages/auth/LoginPage')
      return {
        Component: () => (
          <GuestOnly>
            <LoginPage />
          </GuestOnly>
        ),
      }
    },
  },
  {
    path: '/register',
    lazy: async () => {
      const { RegisterPage } = await import('./pages/auth/RegisterPage')
      return {
        Component: () => (
          <GuestOnly>
            <RegisterPage />
          </GuestOnly>
        ),
      }
    },
  },
  {
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    children: [
      { index: true, lazy: async () => ({ Component: (await import('./pages/DashboardPage')).DashboardPage }) },
      { path: 'products', lazy: async () => ({ Component: (await import('./pages/products/ProductsPage')).ProductsPage }) },
      { path: 'orders', lazy: async () => ({ Component: (await import('./pages/orders/OrdersPage')).OrdersPage }) },
      { path: 'orders/new', lazy: async () => ({ Component: (await import('./pages/orders/NewOrderPage')).NewOrderPage }) },
      {
        path: 'orders/:id',
        lazy: async () => ({ Component: (await import('./pages/orders/OrderDetailPage')).OrderDetailPage }),
      },
      { path: '*', lazy: async () => ({ Component: (await import('./pages/NotFoundPage')).NotFoundPage }) },
    ],
  },
]
