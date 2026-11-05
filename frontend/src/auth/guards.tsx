import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { redirectFromState } from './redirect'
import { useSessionState } from './useSession'

export interface LoginRedirectState {
  from?: string
}

/** Rutas protegidas: sin sesión, a /login recordando a dónde se quería ir. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { session } = useSessionState()
  const location = useLocation()
  if (!session) {
    const state: LoginRedirectState = { from: `${location.pathname}${location.search}` }
    return <Navigate to="/login" replace state={state} />
  }
  return children
}

/**
 * Login y registro: con sesión activa no tienen sentido. Al iniciar sesión, esta
 * guarda es la que lleva al destino que se pidió antes del login (solo rutas internas).
 */
export function GuestOnly({ children }: { children: ReactNode }) {
  const { session } = useSessionState()
  const location = useLocation()
  if (session) return <Navigate to={redirectFromState(location.state)} replace />
  return children
}
