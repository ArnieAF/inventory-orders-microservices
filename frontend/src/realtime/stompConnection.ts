import { Client, ReconnectionTimeMode, type IMessage } from '@stomp/stompjs'
import type { ConnectionStatus } from './notificationCenter'

export const NOTIFICATION_DESTINATIONS = ['/topic/notifications', '/user/queue/notifications'] as const

/** `ws(s)://<host>/ws` del mismo origen (nginx o el proxy de Vite lo llevan al gateway). */
export function realtimeUrl(location: Pick<Location, 'protocol' | 'host'> = window.location): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${location.host}/ws`
}

export interface RealtimeConnectionOptions {
  url: string
  /** Token vigente; `null` detiene la conexión. Se lee en cada (re)conexión. */
  getToken: () => string | null
  onMessage: (payload: unknown) => void
  /** `isReconnect` = ya hubo una conexión antes (toca re-sincronizar contador e historial). */
  onConnected: (isReconnect: boolean) => void
  onStatus: (status: ConnectionStatus) => void
  /** Inyectable para tests. */
  createClient?: (config: ConstructorParameters<typeof Client>[0]) => Client
}

export interface RealtimeConnection {
  start(): void
  stop(): Promise<void>
}

function parseBody(message: IMessage): unknown {
  try {
    return JSON.parse(message.body) as unknown
  } catch {
    return null
  }
}

export function createRealtimeConnection(options: RealtimeConnectionOptions): RealtimeConnection {
  const { url, getToken, onMessage, onConnected, onStatus } = options
  let hasConnected = false
  let stopped = false

  const client = (options.createClient ?? ((config) => new Client(config)))({
    brokerURL: url,
    // Reintento exponencial: 1 s, 2 s, 4 s… hasta 30 s. Se reinicia tras conectar.
    reconnectDelay: 1_000,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    maxReconnectDelay: 30_000,
    connectionTimeout: 10_000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    debug: () => undefined,
    beforeConnect: (stompClient) => {
      const token = getToken()
      if (!token || stopped) {
        void stompClient.deactivate()
        onStatus('idle')
        return
      }
      stompClient.connectHeaders = { Authorization: `Bearer ${token}` }
      onStatus('connecting')
    },
    onConnect: () => {
      for (const destination of NOTIFICATION_DESTINATIONS) {
        client.subscribe(destination, (message) => onMessage(parseBody(message)))
      }
      onStatus('online')
      onConnected(hasConnected)
      hasConnected = true
    },
    onStompError: () => onStatus('offline'),
    onWebSocketClose: () => {
      if (!stopped) onStatus('offline')
    },
    onWebSocketError: () => onStatus('offline'),
  })

  return {
    start() {
      stopped = false
      client.activate()
    },
    async stop() {
      stopped = true
      await client.deactivate()
      onStatus('idle')
    },
  }
}
