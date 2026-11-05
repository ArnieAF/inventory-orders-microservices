import { useSyncExternalStore } from 'react'
import type { Session, SessionState } from './session'
import { sessionStore } from './sessionStore'

export function useSessionState(): SessionState {
  return useSyncExternalStore(sessionStore.subscribe, sessionStore.getState, sessionStore.getState)
}

/** Sesión actual; solo usar dentro de rutas protegidas. */
export function useSession(): Session {
  const { session } = useSessionState()
  if (!session) throw new Error('useSession requiere una sesión activa')
  return session
}
