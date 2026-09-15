import { ERROR_CODES } from './types'
import type {
  ApiErrorEnvelope,
  CreateDownload,
  DirectoryListing,
  HealthResponse,
  HistoryEntry,
  ServerSettings,
  Task,
  VersionResponse,
} from './types'
import { STORAGE_KEYS, readStorage } from '../utils/storage'

export const API_BASE = '/api/v1'
export const EVENTS_PATH = `${API_BASE}/events`

export interface ApiErrorInit {
  code: string
  params?: Record<string, unknown>
  detail?: string | null
  status?: number
}

/**
 * Every failure surfaces as an `ApiError` so the UI can render
 * `errors.<CODE>` with the `params` from the server envelope.
 */
export class ApiError extends Error {
  readonly code: string
  readonly params: Record<string, unknown>
  readonly detail: string | null
  readonly status: number

  constructor(init: ApiErrorInit) {
    super(init.detail ?? `API error: ${init.code}`)
    this.name = 'ApiError'
    this.code = init.code
    this.params = init.params ?? {}
    this.detail = init.detail ?? null
    this.status = init.status ?? 0
  }

  /** Message key for the active locale, e.g. `errors.TASK_NOT_FOUND`. */
  get messageKey(): string {
    return errorMessageKey(this.code)
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError
}

/** Unknown codes collapse onto `errors.INTERNAL`. */
export function errorMessageKey(code: string | undefined | null): string {
  if (code && (ERROR_CODES as readonly string[]).includes(code)) return `errors.${code}`
  return 'errors.INTERNAL'
}

/** Turns any thrown value into an `ApiError`, client side failures become `NETWORK`. */
export function toApiError(value: unknown): ApiError {
  if (isApiError(value)) return value
  if (value instanceof DOMException && value.name === 'AbortError') {
    return new ApiError({ code: 'NETWORK', detail: 'request aborted' })
  }
  const detail = value instanceof Error ? value.message : String(value)
  return new ApiError({ code: 'NETWORK', detail })
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
  /** Extra query parameters, `undefined` values are dropped. */
  query?: Record<string, string | number | boolean | undefined>
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const normalized = path.startsWith('/') ? path : `/${path}`
  const url = `${API_BASE}${normalized}`
  if (!query) return url
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined) continue
    search.set(key, String(value))
  }
  const qs = search.toString()
  return qs ? `${url}?${qs}` : url
}

export function authHeaders(): Record<string, string> {
  const token = readStorage(STORAGE_KEYS.token)
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** Thin fetch wrapper: JSON in, JSON out, `ApiError` on every failure. */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal, query } = options
  const headers: Record<string, string> = { Accept: 'application/json', ...authHeaders() }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers,
      signal,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (error) {
    throw toApiError(error)
  }

  if (response.status === 204) return undefined as T

  const text = await response.text()
  let payload: unknown = undefined
  if (text.length > 0) {
    try {
      payload = JSON.parse(text)
    } catch {
      if (!response.ok) {
        throw new ApiError({ code: 'INTERNAL', status: response.status, detail: text.slice(0, 200) })
      }
      throw new ApiError({ code: 'INTERNAL', status: response.status, detail: 'malformed JSON response' })
    }
  }

  if (!response.ok) {
    const envelope = payload as ApiErrorEnvelope | undefined
    const error = envelope?.error
    throw new ApiError({
      code: error?.code ?? 'INTERNAL',
      params: error?.params,
      detail: error?.detail ?? null,
      status: response.status,
    })
  }

  return payload as T
}

export const api = {
  health: (signal?: AbortSignal) => request<HealthResponse>('/health', { signal }),
  version: (signal?: AbortSignal) => request<VersionResponse>('/version', { signal }),

  getSettings: (signal?: AbortSignal) => request<ServerSettings>('/settings', { signal }),
  updateSettings: (patch: Partial<ServerSettings>, signal?: AbortSignal) =>
    request<ServerSettings>('/settings', { method: 'PUT', body: patch, signal }),

  listDirectories: (path?: string, signal?: AbortSignal) =>
    request<DirectoryListing>('/directories', { signal, query: path === undefined ? {} : { path } }),

  listDownloads: (signal?: AbortSignal) => request<Task[]>('/downloads', { signal }),
  getDownload: (id: string, signal?: AbortSignal) =>
    request<Task>(`/downloads/${encodeURIComponent(id)}`, { signal }),
  createDownload: (payload: CreateDownload, signal?: AbortSignal) =>
    request<Task>('/downloads', { method: 'POST', body: payload, signal }),
  deleteDownload: (id: string, deleteFile = false, signal?: AbortSignal) =>
    request<void>(`/downloads/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      signal,
      query: { deleteFile },
    }),
  startDownload: (id: string, signal?: AbortSignal) =>
    request<Task>(`/downloads/${encodeURIComponent(id)}/start`, { method: 'POST', signal }),
  pauseDownload: (id: string, signal?: AbortSignal) =>
    request<Task>(`/downloads/${encodeURIComponent(id)}/pause`, { method: 'POST', signal }),
  resumeDownload: (id: string, signal?: AbortSignal) =>
    request<Task>(`/downloads/${encodeURIComponent(id)}/resume`, { method: 'POST', signal }),
  setConnections: (id: string, connections: number, signal?: AbortSignal) =>
    request<Task>(`/downloads/${encodeURIComponent(id)}/connections`, {
      method: 'PATCH',
      body: { connections },
      signal,
    }),

  history: (limit = 100, signal?: AbortSignal) =>
    request<HistoryEntry[]>('/history', { signal, query: { limit } }),
  clearHistory: (signal?: AbortSignal) => request<void>('/history', { method: 'DELETE', signal }),
}
