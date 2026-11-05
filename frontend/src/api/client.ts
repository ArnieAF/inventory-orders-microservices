import type { ApiErrorBody, ApiErrorDetail } from './types'

/** Códigos propios del cliente (no vienen del servidor). */
export type ClientErrorCode = 'NETWORK_ERROR' | 'UNKNOWN_ERROR' | 'FORBIDDEN' | 'CONFLICT' | 'TOO_MANY_REQUESTS'

/**
 * Error de API normalizado. Nunca guarda el `message` del servidor: la UI solo
 * trabaja con `code` (mapeado a español en `errors.ts`), `status` y `details` saneados.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: readonly ApiErrorDetail[]

  constructor(status: number, code: string, details: readonly ApiErrorDetail[] = []) {
    super(code)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

export function isApiError(error: unknown, code?: string): error is ApiError {
  return error instanceof ApiError && (code === undefined || error.code === code)
}

type QueryValue = string | number | boolean | null | undefined

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  query?: Record<string, QueryValue>
  signal?: AbortSignal
  /** `false` para rutas públicas (login/registro): sin token y sin redirección ante 401. */
  auth?: boolean
}

export interface ApiClientDeps {
  /** Token vigente o `null` (si caducó, quien lo implementa debe cerrar la sesión). */
  getToken: () => string | null
  /** Se llama ante un 401 en una ruta autenticada: limpiar sesión y volver a /login. */
  onUnauthorized: () => void
  fetchImpl?: typeof fetch
}

export interface ApiClient {
  request<T>(path: string, options?: RequestOptions): Promise<T>
}

const MAX_DETAILS = 100

function codeFromStatus(status: number): string {
  if (status === 400) return 'VALIDATION_ERROR'
  if (status === 401) return 'UNAUTHORIZED'
  if (status === 403) return 'FORBIDDEN'
  if (status === 404) return 'NOT_FOUND'
  if (status === 409) return 'CONFLICT'
  if (status === 429) return 'TOO_MANY_REQUESTS'
  if (status === 502 || status === 503 || status === 504) return 'SERVICE_UNAVAILABLE'
  if (status >= 500) return 'INTERNAL_ERROR'
  return 'UNKNOWN_ERROR'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Se queda solo con los campos conocidos y del tipo correcto. */
export function sanitizeDetails(raw: unknown): ApiErrorDetail[] {
  if (!Array.isArray(raw)) return []
  const details: ApiErrorDetail[] = []
  for (const item of raw.slice(0, MAX_DETAILS)) {
    if (!isRecord(item)) continue
    const detail: ApiErrorDetail = {}
    if (typeof item.field === 'string') detail.field = item.field
    if (typeof item.message === 'string') detail.message = item.message
    if (typeof item.productId === 'string') detail.productId = item.productId
    if (typeof item.requested === 'number' && Number.isFinite(item.requested)) detail.requested = item.requested
    if (typeof item.available === 'number' && Number.isFinite(item.available)) detail.available = item.available
    details.push(detail)
  }
  return details
}

/** Lee el cuerpo de error común (§5). Si no es JSON válido, deduce el código del estado HTTP. */
export function parseErrorBody(status: number, text: string): ApiError {
  let body: Partial<ApiErrorBody> | null = null
  if (text) {
    try {
      const parsed: unknown = JSON.parse(text)
      if (isRecord(parsed)) body = parsed
    } catch {
      body = null
    }
  }
  const code =
    body && typeof body.code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(body.code) ? body.code : codeFromStatus(status)
  return new ApiError(status, code, sanitizeDetails(body?.details))
}

export function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  if (!path.startsWith('/api/')) {
    throw new Error('Las rutas de API deben empezar por /api/')
  }
  if (!query) return path
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.append(key, String(value))
  }
  const search = params.toString()
  return search ? `${path}?${search}` : path
}

function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { name?: unknown }).name === 'AbortError'
}

export function createApiClient({ getToken, onUnauthorized, fetchImpl }: ApiClientDeps): ApiClient {
  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const { method = 'GET', body, query, signal, auth = true } = options
    const headers = new Headers({ Accept: 'application/json' })

    if (auth) {
      const token = getToken()
      if (!token) {
        onUnauthorized()
        throw new ApiError(401, 'UNAUTHORIZED')
      }
      headers.set('Authorization', `Bearer ${token}`)
    }

    const init: RequestInit = { method, headers, credentials: 'same-origin', cache: 'no-store' }
    if (signal) init.signal = signal
    if (body !== undefined) {
      headers.set('Content-Type', 'application/json')
      init.body = JSON.stringify(body)
    }

    const doFetch = fetchImpl ?? globalThis.fetch.bind(globalThis)
    let response: Response
    try {
      response = await doFetch(buildUrl(path, query), init)
    } catch (error) {
      if (isAbortError(error)) throw error
      throw new ApiError(0, 'NETWORK_ERROR')
    }

    const text = response.status === 204 ? '' : await response.text().catch(() => '')

    if (!response.ok) {
      const error = parseErrorBody(response.status, text)
      if (response.status === 401 && auth) onUnauthorized()
      throw error
    }

    if (!text) return undefined as T
    try {
      return JSON.parse(text) as T
    } catch {
      throw new ApiError(response.status, 'UNKNOWN_ERROR')
    }
  }

  return { request }
}
