import { useQueryClient } from '@tanstack/react-query'
import { useEffect, type ReactNode } from 'react'
import { sessionStore } from '../auth/sessionStore'
import { useSessionState } from '../auth/useSession'
import { notificationCenter } from './center'
import { invalidateForNotification, resyncAfterReconnect } from './invalidate'
import { createRealtimeConnection, realtimeUrl } from './stompConnection'

/**
 * Mantiene la conexión STOMP mientras hay sesión. Al cerrar sesión (o caducar el
 * token) el efecto se limpia y la conexión se cierra.
 */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const { session } = useSessionState()
  const queryClient = useQueryClient()
  const token = session?.token ?? null

  useEffect(() => {
    if (!token) return
    const connection = createRealtimeConnection({
      url: realtimeUrl(),
      getToken: () => sessionStore.getValidToken(),
      onMessage: (payload) => {
        const currentUserId = sessionStore.getState().session?.user.id ?? null
        const result = notificationCenter.ingest(payload, currentUserId)
        if (result.status === 'accepted' && result.notification) {
          invalidateForNotification(queryClient, result.notification)
        }
      },
      onConnected: (isReconnect) => {
        if (isReconnect) resyncAfterReconnect(queryClient)
      },
      onStatus: (status) => notificationCenter.setConnection(status),
    })
    connection.start()
    return () => {
      void connection.stop()
    }
  }, [token, queryClient])

  return children
}
