import { sessionStore } from '../auth/sessionStore'
import { createApiClient } from './client'

/** Cliente compartido: token de la sesión actual y cierre de sesión ante 401. */
export const api = createApiClient({
  getToken: () => sessionStore.getValidToken(),
  onUnauthorized: () => sessionStore.signOut('unauthorized'),
})
