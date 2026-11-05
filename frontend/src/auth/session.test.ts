import { describe, expect, it, vi } from 'vitest'
import type { AuthResponse } from '../api/types'
import { safeRedirectTarget } from './redirect'
import { EXPIRY_SKEW_MS, createSessionStore, parseStoredSession } from './session'

const AUTH: AuthResponse = {
  accessToken: 'jwt',
  tokenType: 'Bearer',
  expiresIn: 3600,
  user: { id: 'u1', username: 'marta.ops', email: 'marta@almacen.test' },
}

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial))
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    removeItem: (key: string) => void data.delete(key),
    data,
  }
}

function clock(start = 1_000_000) {
  let now = start
  const timers: Array<{ at: number; fn: () => void }> = []
  return {
    now: () => now,
    setTimer: (fn: () => void, ms: number) => {
      const timer = { at: now + ms, fn }
      timers.push(timer)
      return timer
    },
    clearTimer: (handle: unknown) => {
      const index = timers.indexOf(handle as (typeof timers)[number])
      if (index >= 0) timers.splice(index, 1)
    },
    advance(ms: number) {
      now += ms
      for (const timer of [...timers]) {
        if (timer.at <= now) {
          timers.splice(timers.indexOf(timer), 1)
          timer.fn()
        }
      }
    },
  }
}

describe('session store', () => {
  it('guarda token en memoria y sessionStorage con caducidad según expiresIn', () => {
    const storage = memoryStorage()
    const time = clock()
    const store = createSessionStore({ storage, ...time })
    const session = store.signIn(AUTH)

    expect(session.expiresAt).toBe(time.now() + 3600 * 1000 - EXPIRY_SKEW_MS)
    expect(store.getValidToken()).toBe('jwt')
    expect(parseStoredSession(storage.getItem('micro.session'))).toEqual(session)
  })

  it('cierra la sesión sola al caducar y avisa a los oyentes (WebSocket, caché)', () => {
    const storage = memoryStorage()
    const time = clock()
    const store = createSessionStore({ storage, ...time })
    const onSignOut = vi.fn()
    const onChange = vi.fn()
    store.onSignOut(onSignOut)
    store.subscribe(onChange)
    store.signIn(AUTH)

    time.advance(3600 * 1000)

    expect(store.getState()).toEqual({ session: null, logoutReason: 'expired' })
    expect(onSignOut).toHaveBeenCalledWith('expired')
    expect(storage.getItem('micro.session')).toBeNull()
    expect(onChange).toHaveBeenCalled()
  })

  it('getValidToken no entrega un token caducado aunque el temporizador no haya saltado', () => {
    const time = clock()
    const store = createSessionStore({ storage: null, now: time.now, setTimer: () => 0, clearTimer: () => undefined })
    store.signIn(AUTH)
    time.advance(3600 * 1000)
    expect(store.getValidToken()).toBeNull()
    expect(store.getState().logoutReason).toBe('expired')
  })

  it('signOut por 401 deja el motivo para avisar en /login y es idempotente', () => {
    const store = createSessionStore({ storage: null })
    const onSignOut = vi.fn()
    store.onSignOut(onSignOut)
    store.signIn(AUTH)
    store.signOut('unauthorized')
    store.signOut('unauthorized')
    expect(store.getState()).toEqual({ session: null, logoutReason: 'unauthorized' })
    expect(onSignOut).toHaveBeenCalledTimes(1)
  })

  it('restaura la sesión vigente de la pestaña y descarta la caducada', () => {
    const time = clock()
    const valid = JSON.stringify({ token: 't', user: AUTH.user, expiresAt: time.now() + 60_000 })
    const restored = createSessionStore({ storage: memoryStorage({ 'micro.session': valid }), ...time })
    expect(restored.getState().session?.token).toBe('t')

    const expiredStorage = memoryStorage({
      'micro.session': JSON.stringify({ token: 't', user: AUTH.user, expiresAt: time.now() - 1 }),
    })
    const expired = createSessionStore({ storage: expiredStorage, ...time })
    expect(expired.getState()).toEqual({ session: null, logoutReason: 'expired' })
    expect(expiredStorage.getItem('micro.session')).toBeNull()
  })

  it('ignora contenido manipulado o corrupto en sessionStorage', () => {
    expect(parseStoredSession('{no json')).toBeNull()
    expect(parseStoredSession(JSON.stringify({ token: '', user: AUTH.user, expiresAt: 1 }))).toBeNull()
    expect(parseStoredSession(JSON.stringify({ token: 't', user: { id: 1 }, expiresAt: 1 }))).toBeNull()
    expect(parseStoredSession(JSON.stringify({ token: 't', user: AUTH.user, expiresAt: 'mañana' }))).toBeNull()
  })
})

describe('safeRedirectTarget', () => {
  it('solo permite rutas internas', () => {
    expect(safeRedirectTarget('/orders/abc?x=1')).toBe('/orders/abc?x=1')
    expect(safeRedirectTarget('https://evil.example')).toBe('/')
    expect(safeRedirectTarget('//evil.example')).toBe('/')
    expect(safeRedirectTarget('/\\evil.example')).toBe('/')
    expect(safeRedirectTarget('/login')).toBe('/')
    expect(safeRedirectTarget(42)).toBe('/')
  })
})
