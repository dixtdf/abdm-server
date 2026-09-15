/**
 * Wire contract for `docs/api.md` (v1). This file mirrors the document one to
 * one: every field name, the ten download states and the error codes.
 */

export const DOWNLOAD_STATES = [
  'QUEUED',
  'CONNECTING',
  'DOWNLOADING',
  'PAUSING',
  'PAUSED',
  'COMPLETING',
  'COMPLETED',
  'FAILED',
  'CANCELED',
  'RECOVERING',
] as const

export type DownloadState = (typeof DOWNLOAD_STATES)[number]

/** Catalogue section of `docs/api.md`. */
export const ERROR_CODES = [
  'INVALID_URL',
  'UNSUPPORTED_SCHEME',
  'DOWNLOAD_DIRECTORY_NOT_FOUND',
  'PATH_OUTSIDE_DOWNLOAD_ROOT',
  'TASK_NOT_FOUND',
  'TASK_ALREADY_EXISTS',
  'INVALID_CONNECTION_COUNT',
  'NETWORK',
  'SERVER_NO_RANGE_SUPPORT',
  'DISK_FULL',
  'CHECKSUM_MISMATCH',
  'ENGINE_UNAVAILABLE',
  'UNSUPPORTED_OPERATION',
  'INTERNAL',
  'UNAUTHORIZED',
] as const

export type ErrorCode = (typeof ERROR_CODES)[number] | (string & {})

/** `total === -1` means unknown size, `etaSeconds === -1` means unknown ETA. */
export const UNKNOWN_BYTES = -1
export const UNKNOWN_ETA = -1

export interface Task {
  id: string
  url: string
  fileName: string
  folder: string
  path: string
  state: DownloadState
  downloaded: number
  total: number
  progress: number
  speed: number
  averageSpeed: number
  connections: number
  activeConnections: number
  etaSeconds: number
  supportsRange: boolean
  hls: boolean
  speedLimit: number
  checksum: string | null
  error: string | null
  createdAt: number
  startedAt: number | null
  completedAt: number | null
  queuePosition: number | null
}

/** Only `url` is mandatory; the rest falls back to `Settings`. */
export interface CreateDownload {
  url: string
  fileName?: string
  folder?: string
  connections?: number
  headers?: Record<string, string>
  cookies?: string
  referer?: string
  userAgent?: string
  proxy?: string
  speedLimit?: number
  checksum?: string
  hls?: boolean
  overwrite?: boolean
  startImmediately?: boolean
}

/** Server side `Settings` (language/theme are web preferences, not settings). */
export interface ServerSettings {
  downloadRoot: string
  defaultFolder: string
  defaultConnections: number
  maxConcurrentDownloads: number
  globalSpeedLimit: number
  resumeOnStartup: boolean
  progressIntervalMs: number
  authMode: 'none' | 'token' | (string & {})
  maxConnections: number
}

export interface DirectoryEntry {
  name: string
  path: string
}

export interface DirectoryListing {
  path: string
  parent: string | null
  directories: DirectoryEntry[]
}

export interface HistoryEntry {
  id: string
  url: string
  fileName: string
  folder: string
  size: number
  checksum: string | null
  outcome: string
  finishedAt: number
}

export interface HealthResponse {
  status: string
  uptimeSeconds: number
  version: string
  engine: string
}

export interface VersionResponse {
  version: string
  apiVersion: string
  engine: string
  engineVersion: string
  abdmVersion: string
  abdmCommit: string
  java: string
  os: string
  startedAt: number
}

export interface ApiErrorEnvelope {
  error: {
    code: string
    params?: Record<string, unknown>
    detail?: string | null
  }
}

export interface DownloadProgress {
  downloaded: number
  total: number
  speed: number
  averageSpeed: number
  connections: number
  activeConnections: number
  etaSeconds: number
  state: DownloadState
}

export interface ServerInfo {
  version: string
  engine: string
  apiVersion: string
}

export type ServerEvent =
  | { type: 'hello'; server: ServerInfo; tasks: Task[] }
  | { type: 'download.progress'; taskId: string; progress: DownloadProgress }
  | { type: 'download.state'; task: Task; previous: DownloadState | null; state: DownloadState; error: string | null }
  | { type: 'download.added'; task: Task }
  | { type: 'download.connections'; task: Task; requested: number; active: number }
  | { type: 'download.removed'; taskId: string; deletedFile: boolean }
  | { type: 'pong' }

export type ServerEventType = ServerEvent['type']

/** States the UI may pause, i.e. everything that is still running. */
export const PAUSABLE_STATES: readonly DownloadState[] = [
  'QUEUED',
  'CONNECTING',
  'DOWNLOADING',
  'RECOVERING',
]

export function isPausable(state: DownloadState): boolean {
  return PAUSABLE_STATES.includes(state)
}

export function isResumable(state: DownloadState): boolean {
  return state === 'PAUSED' || state === 'FAILED' || state === 'CANCELED'
}

export function isActive(state: DownloadState): boolean {
  return state === 'CONNECTING' || state === 'DOWNLOADING' || state === 'COMPLETING' || state === 'RECOVERING'
}

export function isFinished(state: DownloadState): boolean {
  return state === 'COMPLETED' || state === 'FAILED' || state === 'CANCELED'
}
