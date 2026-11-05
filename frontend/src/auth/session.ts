import type { AuthResponse, User } from '../api/types'

export interface Session {
  token: string
  user: User
  /** Epoch en ms a partir del cual el token ya no se usa. */
  expiresAt: number
}

/** `expired`: se agotó `expiresIn`. `unauthorized`: el servidor respondió 401. */
export type LogoutReason = 'manual' | 'expired' | 'unauthorized'

export interface SessionState {
  session: Session | null
  /** Motivo del último cierre de sesión (para avisar en /login). Se limpia al iniciar sesión. */
  logoutReason: LogoutReason | null
}

export interface SessionStore {
  getState: () => SessionState
  subscribe: (listener: () => void) => () => void
  /** Token vigente o `null`. Si ha caducado, cierra la sesión con motivo `expired`. */
  getValidToken: () => string | null
  signIn: (auth: AuthResponse) => Session
  signOut: (reason?: LogoutReason) => void
  /** Efectos al cerrar sesión (cerrar WebSocket, vaciar caché…). */
  onSignOut: (listener: (reason: LogoutReason) => void) => () => void
}

export interface SessionStoreOptions {
  storage?: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null
  storageKey?: string
  now?: () => number
  setTimer?: (callback: () => void, ms: number) => unknown
  clearTimer?: (handle: unknown) => void
}

/** Margen para no usar un token a punto de caducar. */
export const EXPIRY_SKEW_MS = 10_000
const MAX_TIMER_MS = 2_147_483_647

function isUser(value: unknown): value is User {
  if (typeof value !== 'object' || value === null) return false
  const user = value as Record<string, unknown>
  return typeof user.id === 'string' && typeof user.username === 'string' && typeof user.email === 'string'
}

/** Valida lo leído de `sessionStorage`: si algo no encaja, se descarta. */
export function parseStoredSession(raw: string | null): Session | null {
  if (!raw) return null
  try {
    const value: unknown = JSON.parse(raw)
    if (typeof value !== 'object' || value === null) return null
    const record = value as Record<string, unknown>
    if (typeof record.token !== 'string' || record.token.length === 0) return null
    if (typeof record.expiresAt !== 'number' || !Number.isFinite(record.expiresAt)) return null
    if (!isUser(record.user)) return null
    return {
      token: record.token,
      expiresAt: record.expiresAt,
      user: { id: record.user.id, username: record.user.username, email: record.user.email },
    }
  } catch {
    return null
  }
}

export function createSessionStore(options: SessionStoreOptions = {}): SessionStore {
  const {
    storage = null,
    storageKey = 'micro.session',
    now = () => Date.now(),
    setTimer = (callback, ms) => setTimeout(callback, ms),
    clearTimer = (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
  } = options

  const listeners = new Set<() => void>()
  const signOutListeners = new Set<(reason: LogoutReason) => void>()
  let timer: unknown = null
  let state: SessionState = { session: null, logoutReason: null }

  function emit() {
    for (const listener of listeners) listener()
  }

  function persist(session: Session | null) {
    if (!storage) return
    try {
      if (session) storage.setItem(storageKey, JSON.stringify(session))
      else storage.removeItem(storageKey)
    } catch {
      // Almacenamiento lleno o bloqueado: la sesión sigue en memoria.
    }
  }

  function cancelTimer() {
    if (timer !== null) {
      clearTimer(timer)
      timer = null
    }
  }

  function schedule(session: Session) {
    cancelTimer()
    const remaining = Math.max(0, session.expiresAt - now())
    timer = setTimer(() => {
      timer = null
      signOut('expired')
    }, Math.min(remaining, MAX_TIMER_MS))
  }

  function signOut(reason: LogoutReason = 'manual') {
    if (!state.session) return
    cancelTimer()
    persist(null)
    state = { session: null, logoutReason: reason }
    for (const listener of signOutListeners) listener(reason)
    emit()
  }

  function signIn(auth: AuthResponse): Session {
    const lifetimeMs = Math.max(0, auth.expiresIn * 1000 - EXPIRY_SKEW_MS)
    const session: Session = {
      token: auth.accessToken,
      user: { id: auth.user.id, username: auth.user.username, email: auth.user.email },
      expiresAt: now() + lifetimeMs,
    }
    persist(session)
    state = { session, logoutReason: null }
    schedule(session)
    emit()
    return session
  }

  // Restaurar la sesión de esta pestaña (si sigue vigente).
  let stored: Session | null = null
  try {
    stored = storage ? parseStoredSession(storage.getItem(storageKey)) : null
  } catch {
    stored = null
  }
  if (stored && stored.expiresAt > now()) {
    state = { session: stored, logoutReason: null }
    schedule(stored)
  } else if (stored) {
    persist(null)
    state = { session: null, logoutReason: 'expired' }
  } else {
    persist(null)
  }

  return {
    getState: () => state,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    getValidToken() {
      const { session } = state
      if (!session) return null
      if (session.expiresAt <= now()) {
        signOut('expired')
        return null
      }
      return session.token
    },
    signIn,
    signOut,
    onSignOut(listener) {
      signOutListeners.add(listener)
      return () => signOutListeners.delete(listener)
    },
  }
}
