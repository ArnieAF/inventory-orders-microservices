import { createSessionStore } from './session'

function safeSessionStorage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.sessionStorage : null
  } catch {
    return null
  }
}

/** Sesión de la pestaña: memoria + sessionStorage (no se comparte entre pestañas). */
export const sessionStore = createSessionStore({ storage: safeSessionStorage() })
