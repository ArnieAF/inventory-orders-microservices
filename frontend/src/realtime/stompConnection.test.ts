import { ReconnectionTimeMode, type Client, type IMessage, type StompConfig } from '@stomp/stompjs'
import { describe, expect, it, vi } from 'vitest'
import { NOTIFICATION_DESTINATIONS, createRealtimeConnection, realtimeUrl } from './stompConnection'

/** Cliente STOMP falso: guarda la configuración para disparar sus callbacks a mano. */
function fakeClient() {
  let config: StompConfig = {}
  const subscriptions: Array<{ destination: string; callback: (message: IMessage) => void }> = []
  const client = {
    connectHeaders: {},
    activate: vi.fn(),
    deactivate: vi.fn(() => Promise.resolve()),
    subscribe: vi.fn((destination: string, callback: (message: IMessage) => void) => {
      subscriptions.push({ destination, callback })
      return { id: destination, unsubscribe: vi.fn() }
    }),
  }
  const createClient = (cfg: StompConfig | undefined) => {
    config = cfg ?? {}
    return client as unknown as Client
  }
  return { client, createClient, subscriptions, config: () => config }
}

function setup(token: string | null = 'jwt-1') {
  const fake = fakeClient()
  const onMessage = vi.fn()
  const onConnected = vi.fn()
  const onStatus = vi.fn()
  let currentToken = token
  const connection = createRealtimeConnection({
    url: 'ws://localhost:3000/ws',
    getToken: () => currentToken,
    onMessage,
    onConnected,
    onStatus,
    createClient: fake.createClient,
  })
  return { fake, connection, onMessage, onConnected, onStatus, setToken: (t: string | null) => (currentToken = t) }
}

describe('conexión STOMP', () => {
  it('usa WebSocket nativo del mismo origen', () => {
    expect(realtimeUrl({ protocol: 'http:', host: 'localhost:3000' })).toBe('ws://localhost:3000/ws')
    expect(realtimeUrl({ protocol: 'https:', host: 'inventario.example' })).toBe('wss://inventario.example/ws')
  })

  it('configura reconexión exponencial y heartbeats de 10 s', () => {
    const { fake } = setup()
    const config = fake.config()
    expect(config.brokerURL).toBe('ws://localhost:3000/ws')
    expect(config.reconnectTimeMode).toBe(ReconnectionTimeMode.EXPONENTIAL)
    expect(config.maxReconnectDelay).toBe(30_000)
    expect(config.heartbeatIncoming).toBe(10_000)
    expect(config.heartbeatOutgoing).toBe(10_000)
  })

  it('manda el JWT en el CONNECT (leído en cada reconexión)', async () => {
    const { fake, setToken } = setup()
    await fake.config().beforeConnect?.(fake.client as unknown as Client)
    expect(fake.client.connectHeaders).toEqual({ Authorization: 'Bearer jwt-1' })
    setToken('jwt-2')
    await fake.config().beforeConnect?.(fake.client as unknown as Client)
    expect(fake.client.connectHeaders).toEqual({ Authorization: 'Bearer jwt-2' })
  })

  it('sin token (sesión cerrada o caducada) no reconecta', async () => {
    const { fake } = setup(null)
    await fake.config().beforeConnect?.(fake.client as unknown as Client)
    expect(fake.client.deactivate).toHaveBeenCalled()
  })

  it('se suscribe a los dos destinos y avisa de reconexión para re-sincronizar', () => {
    const { fake, onConnected, onStatus } = setup()
    const onConnect = fake.config().onConnect
    onConnect?.({} as never)
    expect(fake.subscriptions.map((s) => s.destination)).toEqual([...NOTIFICATION_DESTINATIONS])
    expect(onConnected).toHaveBeenLastCalledWith(false)
    expect(onStatus).toHaveBeenLastCalledWith('online')
    onConnect?.({} as never)
    expect(onConnected).toHaveBeenLastCalledWith(true)
  })

  it('entrega el JSON parseado y null si el cuerpo no es JSON', () => {
    const { fake, onMessage } = setup()
    fake.config().onConnect?.({} as never)
    const [topic] = fake.subscriptions
    topic?.callback({ body: '{"id":"n1"}' } as IMessage)
    topic?.callback({ body: 'no-json' } as IMessage)
    expect(onMessage.mock.calls).toEqual([[{ id: 'n1' }], [null]])
  })

  it('al cerrar sesión se desactiva el cliente', async () => {
    const { fake, connection, onStatus } = setup()
    connection.start()
    expect(fake.client.activate).toHaveBeenCalled()
    await connection.stop()
    expect(fake.client.deactivate).toHaveBeenCalled()
    expect(onStatus).toHaveBeenLastCalledWith('idle')
  })
})
