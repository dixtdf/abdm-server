import { EVENTS_PATH } from './client'
import type { ServerEvent } from './types'
import { STORAGE_KEYS, readStorage } from '../utils/storage'

export type WsStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface EventSocketHandlers {
  onEvent: (event: ServerEvent) => void
  onStatus?: (status: WsStatus, attempt: number) => void
}

export interface EventSocketOptions {
  path?: string
  /** Heartbeat cadence, defaults to 30s as required by the contract. */
  pingIntervalMs?: number
  baseDelayMs?: number
  maxDelayMs?: number
  /** Injectable for tests. */
  urlFactory?: () => string
}

export const DEFAULT_PING_INTERVAL_MS = 30_000
const DEFAULT_BASE_DELAY_MS = 500
const DEFAULT_MAX_DELAY_MS = 15_000

function defaultUrl(path: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const token = readStorage(STORAGE_KEYS.token)
  const query = token ? `?token=${encodeURIComponent(token)}` : ''
  return `${protocol}//${window.location.host}${path}${query}`
}

/**
 * Auto reconnecting client for `/api/v1/events`.
 *
 * * exponential backoff with jitter (500ms → 15s, capped)
 * * 30s ping heartbeat, `pong` frames are swallowed
 * * every frame is handed to the `downloads` store, progress frames never
 *   trigger a REST refetch
 */
export class EventSocket {
  private socket: WebSocket | null = null
  private pingTimer: ReturnType<typeof setInterval> | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private attempt = 0
  private currentStatus: WsStatus = 'idle'
  private manualClose = false

  constructor(
    private readonly handlers: EventSocketHandlers,
    private readonly options: EventSocketOptions = {},
  ) {}

  get status(): WsStatus {
    return this.currentStatus
  }

  get retryAttempt(): number {
    return this.attempt
  }

  start(): void {
    if (this.currentStatus === 'open' || this.currentStatus === 'connecting') return
    this.manualClose = false
    this.connect()
  }

  stop(): void {
    this.manualClose = true
    this.clearTimers()
    if (this.socket) {
      const socket = this.socket
      this.socket = null
      socket.onopen = null
      socket.onmessage = null
      socket.onerror = null
      socket.onclose = null
      try {
        socket.close()
      } catch {
        /* already closed */
      }
    }
    this.setStatus('closed')
  }

  private connect(): void {
    const path = this.options.path ?? EVENTS_PATH
    const url = this.options.urlFactory ? this.options.urlFactory() : defaultUrl(path)
    const connecting = this.attempt === 0 && this.currentStatus === 'idle'
    this.setStatus(connecting ? 'connecting' : 'reconnecting')

    let socket: WebSocket
    try {
      socket = new WebSocket(url)
    } catch {
      this.scheduleReconnect()
      return
    }
    this.socket = socket

    socket.onopen = () => {
      this.attempt = 0
      this.setStatus('open')
      this.startPing()
    }

    socket.onmessage = (message: MessageEvent<string>) => {
      this.handleMessage(message.data)
    }

    socket.onerror = () => {
      /* `onclose` always follows and owns the reconnect. */
    }

    socket.onclose = () => {
      this.stopPing()
      if (this.socket === socket) this.socket = null
      if (this.manualClose) {
        this.setStatus('closed')
        return
      }
      this.scheduleReconnect()
    }
  }

  private handleMessage(raw: unknown): void {
    if (typeof raw !== 'string') {
      this.handlers.onEvent({ type: 'pong' })
      return
    }
    let parsed: ServerEvent
    try {
      parsed = JSON.parse(raw) as ServerEvent
    } catch {
      return
    }
    if (parsed?.type === 'pong') return
    this.handlers.onEvent(parsed)
  }

  private startPing(): void {
    this.stopPing()
    const interval = this.options.pingIntervalMs ?? DEFAULT_PING_INTERVAL_MS
    this.pingTimer = setInterval(() => {
      this.send({ type: 'ping' })
    }, interval)
  }

  private stopPing(): void {
    if (this.pingTimer !== null) {
      clearInterval(this.pingTimer)
      this.pingTimer = null
    }
  }

  private send(payload: unknown): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return
    try {
      this.socket.send(JSON.stringify(payload))
    } catch {
      /* the close handler will reconnect */
    }
  }

  private scheduleReconnect(): void {
    if (this.manualClose) return
    const base = this.options.baseDelayMs ?? DEFAULT_BASE_DELAY_MS
    const max = this.options.maxDelayMs ?? DEFAULT_MAX_DELAY_MS
    const delay = Math.min(base * 2 ** this.attempt, max)
    const jittered = Math.round(delay * (0.8 + Math.random() * 0.4))
    this.attempt += 1
    this.setStatus('reconnecting')
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, jittered)
  }

  private clearTimers(): void {
    this.stopPing()
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  private setStatus(status: WsStatus): void {
    if (this.currentStatus === status) return
    this.currentStatus = status
    this.handlers.onStatus?.(status, this.attempt)
  }
}

export function createEventSocket(
  handlers: EventSocketHandlers,
  options: EventSocketOptions = {},
): EventSocket {
  return new EventSocket(handlers, options)
}
