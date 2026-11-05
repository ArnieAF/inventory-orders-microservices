import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import { vi } from 'vitest'
import type { AuthResponse, User } from '../api/types'
import { sessionStore } from '../auth/sessionStore'

export const TEST_USER: User = { id: '11111111-1111-1111-1111-111111111111', username: 'marta.ops', email: 'marta@almacen.test' }

export function signInTestUser(expiresIn = 3600): void {
  const auth: AuthResponse = { accessToken: 'test-token', tokenType: 'Bearer', expiresIn, user: TEST_USER }
  sessionStore.signIn(auth)
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

export function errorResponse(status: number, code: string, details?: unknown[]): Response {
  return jsonResponse(
    {
      timestamp: '2026-11-05T12:00:00Z',
      status,
      code,
      // Mensaje del servidor en inglés: la UI nunca debe mostrarlo.
      message: 'RAW SERVER MESSAGE should never be shown',
      path: '/api/test',
      ...(details ? { details } : {}),
    },
    status,
  )
}

export interface RecordedRequest {
  method: string
  url: URL
  body: unknown
  headers: Headers
}

type Handler = (request: RecordedRequest) => Response | Promise<Response>

/**
 * `fetch` simulado por método + ruta. Las rutas sin manejador responden 404 en formato común.
 * Devuelve las peticiones registradas para poder comprobar cuerpo, cabeceras y query.
 */
export function mockFetch(handlers: Record<string, Handler>) {
  const requests: RecordedRequest[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const url = new URL(raw, 'http://localhost')
    const method = (init?.method ?? 'GET').toUpperCase()
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    const request: RecordedRequest = { method, url, body, headers: new Headers(init?.headers) }
    requests.push(request)
    const handler = handlers[`${method} ${url.pathname}`]
    return handler ? handler(request) : errorResponse(404, 'NOT_FOUND')
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, requests }
}

export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0, gcTime: Infinity }, mutations: { retry: false } },
  })
}

/** Renderiza rutas reales con un router en memoria y un QueryClient aislado. */
export function renderRoutes(routes: RouteObject[], initialEntry: string) {
  const queryClient = createTestQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [initialEntry] })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...utils, router, queryClient }
}

export function renderPage(element: ReactElement, { path = '/', initialEntry = '/' } = {}) {
  return renderRoutes(
    [
      { path, element },
      { path: '*', element: <p>Otra ruta</p> },
    ],
    initialEntry,
  )
}

export function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalElements: number; totalPages: number }> = {}) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length > 0 ? 1 : 0,
    ...overrides,
  }
}
