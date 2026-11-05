import { describe, expect, it, vi } from 'vitest'
import { errorResponse, jsonResponse } from '../test/utils'
import { ApiError, buildUrl, createApiClient, parseErrorBody, sanitizeDetails } from './client'
import { ERROR_MESSAGES, errorMessage, fieldErrorsFrom } from './errors'

function setup(response: Response | Error | DOMException, token: string | null = 'jwt-123') {
  const fetchImpl = vi.fn<typeof fetch>(() =>
    response instanceof Response ? Promise.resolve(response) : Promise.reject(response),
  )
  const onUnauthorized = vi.fn()
  const client = createApiClient({ getToken: () => token, onUnauthorized, fetchImpl })
  return { client, fetchImpl, onUnauthorized }
}

function lastInit(fetchImpl: ReturnType<typeof vi.fn<typeof fetch>>): RequestInit {
  const call = fetchImpl.mock.calls.at(-1)
  if (!call?.[1]) throw new Error('sin llamada')
  return call[1]
}

describe('createApiClient', () => {
  it('envía el token Bearer, JSON y URL relativa con query', async () => {
    const { client, fetchImpl } = setup(jsonResponse({ ok: true }))
    await client.request('/api/products', { method: 'POST', body: { sku: 'A' }, query: { page: 0, search: 'caja', lowStock: undefined } })

    expect(fetchImpl.mock.calls[0]?.[0]).toBe('/api/products?page=0&search=caja')
    const init = lastInit(fetchImpl)
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer jwt-123')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(init.body).toBe(JSON.stringify({ sku: 'A' }))
    expect(init.method).toBe('POST')
  })

  it('no envía token en rutas públicas', async () => {
    const { client, fetchImpl } = setup(jsonResponse({ accessToken: 'x' }))
    await client.request('/api/auth/login', { method: 'POST', body: {}, auth: false })
    expect(new Headers(lastInit(fetchImpl).headers).has('Authorization')).toBe(false)
  })

  it('devuelve undefined en 204', async () => {
    const { client } = setup(new Response(null, { status: 204 }))
    await expect(client.request('/api/products/1', { method: 'DELETE' })).resolves.toBeUndefined()
  })

  it('normaliza el error común y nunca guarda el mensaje del servidor', async () => {
    const { client } = setup(
      errorResponse(409, 'INSUFFICIENT_STOCK', [{ productId: 'p1', requested: 5, available: 2, extra: '<b>x</b>' }]),
    )
    const error = await client.request('/api/orders', { method: 'POST', body: {} }).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.status).toBe(409)
    expect(apiError.code).toBe('INSUFFICIENT_STOCK')
    expect(apiError.details).toEqual([{ productId: 'p1', requested: 5, available: 2 }])
    expect(apiError.message).toBe('INSUFFICIENT_STOCK')
    expect(JSON.stringify(apiError)).not.toContain('RAW SERVER MESSAGE')
  })

  it('ante 401 en ruta autenticada limpia la sesión', async () => {
    const { client, onUnauthorized } = setup(errorResponse(401, 'UNAUTHORIZED'))
    await expect(client.request('/api/orders')).rejects.toMatchObject({ code: 'UNAUTHORIZED' })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('ante 401 en login NO cierra sesión ni redirige', async () => {
    const { client, onUnauthorized } = setup(errorResponse(401, 'INVALID_CREDENTIALS'))
    await expect(client.request('/api/auth/login', { method: 'POST', body: {}, auth: false })).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS',
    })
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('sin token no llama al servidor y cierra sesión', async () => {
    const { client, fetchImpl, onUnauthorized } = setup(jsonResponse({}), null)
    await expect(client.request('/api/products')).rejects.toMatchObject({ status: 401, code: 'UNAUTHORIZED' })
    expect(fetchImpl).not.toHaveBeenCalled()
    expect(onUnauthorized).toHaveBeenCalled()
  })

  it('un fallo de red se convierte en NETWORK_ERROR', async () => {
    const { client } = setup(new TypeError('Failed to fetch'))
    await expect(client.request('/api/products')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
  })

  it('una cancelación se propaga tal cual (React Query la gestiona)', async () => {
    const { client } = setup(new DOMException('aborted', 'AbortError'))
    await expect(client.request('/api/products')).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('rechaza rutas que no sean /api/', () => {
    expect(() => buildUrl('https://evil.example/api/x')).toThrow()
    expect(() => buildUrl('/ws')).toThrow()
  })
})

describe('parseErrorBody', () => {
  it('deduce el código por estado si el cuerpo no es JSON (p. ej. 502 de un proxy)', () => {
    expect(parseErrorBody(502, '<html>Bad gateway</html>').code).toBe('SERVICE_UNAVAILABLE')
    expect(parseErrorBody(500, '').code).toBe('INTERNAL_ERROR')
    expect(parseErrorBody(404, 'not json').code).toBe('NOT_FOUND')
  })

  it('ignora códigos con formato extraño', () => {
    expect(parseErrorBody(400, JSON.stringify({ code: '<script>' })).code).toBe('VALIDATION_ERROR')
  })

  it('sanea details: solo campos conocidos y con su tipo', () => {
    expect(sanitizeDetails([{ field: 'sku', message: 'must not be blank' }, 'x', null, { requested: '5' }])).toEqual([
      { field: 'sku', message: 'must not be blank' },
      {},
    ])
    expect(sanitizeDetails('nope')).toEqual([])
  })
})

describe('errorMessage', () => {
  it('traduce cada código del contrato a español', () => {
    const codes = [
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
    ]
    for (const code of codes) {
      expect(ERROR_MESSAGES[code], code).toBeTruthy()
      expect(errorMessage(new ApiError(400, code))).toBe(ERROR_MESSAGES[code])
    }
  })

  it('usa un genérico para códigos desconocidos y errores que no son de API', () => {
    expect(errorMessage(new ApiError(418, 'TEAPOT'))).toBe(ERROR_MESSAGES.UNKNOWN_ERROR)
    expect(errorMessage(new Error('stack trace con datos internos'))).toBe(ERROR_MESSAGES.UNKNOWN_ERROR)
  })

  it('permite afinar el texto según el contexto', () => {
    expect(errorMessage(new ApiError(404, 'NOT_FOUND'), { NOT_FOUND: 'Ese pedido no existe.' })).toBe('Ese pedido no existe.')
  })
})

describe('fieldErrorsFrom', () => {
  it('marca el campo de códigos con campo propio', () => {
    expect(fieldErrorsFrom(new ApiError(409, 'SKU_ALREADY_EXISTS'), ['sku', 'name'])).toEqual({
      sku: 'Ya existe un producto con ese SKU.',
    })
  })

  it('mapea VALIDATION_ERROR a campos conocidos con texto genérico en español', () => {
    const error = new ApiError(400, 'VALIDATION_ERROR', [
      { field: 'name', message: 'size must be between 1 and 120' },
      { field: 'unknownField', message: 'x' },
    ])
    expect(fieldErrorsFrom(error, ['sku', 'name'])).toEqual({ name: 'Revisa este valor.' })
  })
})
