/** Solo rutas internas de la app (evita redirecciones abiertas tras el login). */
export function safeRedirectTarget(value: unknown): string {
  if (typeof value !== 'string') return '/'
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) return '/'
  if (value.startsWith('/login') || value.startsWith('/register')) return '/'
  return value
}

/** Lee `state.from` que deja RequireAuth al redirigir a /login. */
export function redirectFromState(state: unknown): string {
  if (typeof state === 'object' && state !== null && 'from' in state) {
    return safeRedirectTarget(state.from)
  }
  return '/'
}
