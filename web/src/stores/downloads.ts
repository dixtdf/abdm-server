import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, toApiError } from '../api/client'
import { createEventSocket, type EventSocket, type WsStatus } from '../api/ws'
import { useUiStore } from './ui'
import type { ApiError } from '../api/client'
import type {
  CreateDownload,
  DownloadProgress,
  HistoryEntry,
  ServerEvent,
  Task,
} from '../api/types'

/** `progress` is a derived ratio, recomputed whenever the byte counters move. */
function deriveProgress(downloaded: number, total: number): number {
  if (!Number.isFinite(downloaded) || !Number.isFinite(total) || total <= 0) return 0
  return Math.min(1, Math.max(0, downloaded / total))
}

function upsert(tasks: Task[], task: Task, mode: 'prepend' | 'replace'): Task[] {
  const index = tasks.findIndex((candidate) => candidate.id === task.id)
  if (index < 0) {
    return mode === 'prepend' ? [task, ...tasks] : [...tasks, task]
  }
  const previous = tasks[index] as Task
  const next = tasks.slice()
  next[index] = task
  if (mode === 'replace' && previous === task) return tasks
  return next
}

function progressChanged(current: Task, next: Task): boolean {
  return (
    current.downloaded !== next.downloaded ||
    current.total !== next.total ||
    current.speed !== next.speed ||
    current.averageSpeed !== next.averageSpeed ||
    current.connections !== next.connections ||
    current.activeConnections !== next.activeConnections ||
    current.etaSeconds !== next.etaSeconds ||
    current.state !== next.state
  )
}

function mergeProgress(task: Task, progress: DownloadProgress): Task {
  return {
    ...task,
    downloaded: progress.downloaded,
    total: progress.total,
    progress: deriveProgress(progress.downloaded, progress.total),
    speed: progress.speed,
    averageSpeed: progress.averageSpeed,
    connections: progress.connections,
    activeConnections: progress.activeConnections,
    etaSeconds: progress.etaSeconds,
    state: progress.state,
  }
}

/**
 * Pure reducer for the `/api/v1/events` stream. Kept free of Pinia so it can be
 * unit tested directly; the store below only wraps it.
 *
 * Unknown ids for progress frames are ignored on purpose: a throttled frame may
 * race the REST list, and progress must never create or refetch a task.
 */
export function applyEvent(tasks: Task[], event: ServerEvent): Task[] {
  switch (event.type) {
    case 'hello':
      return [...event.tasks]

    case 'download.added':
      return upsert(tasks, event.task, 'prepend')

    case 'download.progress': {
      const index = tasks.findIndex((task) => task.id === event.taskId)
      if (index < 0) return tasks
      const current = tasks[index] as Task
      const next = mergeProgress(current, event.progress)
      if (!progressChanged(current, next)) return tasks
      const copy = tasks.slice()
      copy[index] = next
      return copy
    }

    case 'download.state':
    case 'download.connections':
      return upsert(tasks, event.task, 'replace')

    case 'download.removed': {
      const next = tasks.filter((task) => task.id !== event.taskId)
      return next.length === tasks.length ? tasks : next
    }

    default:
      return tasks
  }
}

export const useDownloadsStore = defineStore('downloads', () => {
  const tasks = ref<Task[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<ApiError | null>(null)
  const wsStatus = ref<WsStatus>('idle')

  const history = ref<HistoryEntry[]>([])
  const historyLoading = ref(false)
  const historyLoaded = ref(false)
  const historyError = ref<ApiError | null>(null)

  let socket: EventSocket | null = null

  const tasksById = computed(() => {
    const map = new Map<string, Task>()
    for (const task of tasks.value) map.set(task.id, task)
    return map
  })

  const taskById = (id: string): Task | null => tasksById.value.get(id) ?? null

  const activeCount = computed(
    () =>
      tasks.value.filter((task) =>
        ['QUEUED', 'CONNECTING', 'DOWNLOADING', 'PAUSING', 'COMPLETING', 'RECOVERING'].includes(task.state),
      ).length,
  )
  const finishedCount = computed(
    () => tasks.value.filter((task) => ['COMPLETED', 'FAILED', 'CANCELED'].includes(task.state)).length,
  )
  const totalSpeed = computed(() =>
    tasks.value.reduce((sum, task) => (task.state === 'DOWNLOADING' ? sum + task.speed : sum), 0),
  )
  const isEmpty = computed(() => loaded.value && tasks.value.length === 0)

  /** Every frame of the event stream lands here. */
  function applyServerEvent(event: ServerEvent): void {
    const next = applyEvent(tasks.value, event)
    if (next !== tasks.value) tasks.value = next
  }

  async function load(): Promise<void> {
    loading.value = true
    try {
      const list = await api.listDownloads()
      tasks.value = list
      loaded.value = true
      error.value = null
    } catch (caught) {
      error.value = toApiError(caught)
    } finally {
      loading.value = false
    }
  }

  async function refresh(): Promise<void> {
    await load()
  }

  async function create(payload: CreateDownload): Promise<Task> {
    const created = await api.createDownload(payload)
    applyServerEvent({ type: 'download.added', task: created })
    return created
  }

  async function start(id: string): Promise<void> {
    const task = await api.startDownload(id)
    applyServerEvent({ type: 'download.state', task, previous: null, state: task.state, error: task.error })
  }

  async function pause(id: string): Promise<void> {
    const task = await api.pauseDownload(id)
    applyServerEvent({ type: 'download.state', task, previous: null, state: task.state, error: task.error })
  }

  async function resume(id: string): Promise<void> {
    const task = await api.resumeDownload(id)
    applyServerEvent({ type: 'download.state', task, previous: null, state: task.state, error: task.error })
  }

  async function remove(id: string, deleteFile = false): Promise<void> {
    await api.deleteDownload(id, deleteFile)
    applyServerEvent({ type: 'download.removed', taskId: id, deletedFile: deleteFile })
  }

  async function setConnections(id: string, connections: number): Promise<Task> {
    const task = await api.setConnections(id, connections)
    applyServerEvent({
      type: 'download.connections',
      task,
      requested: connections,
      active: task.activeConnections,
    })
    return task
  }

  async function loadHistory(limit = 100): Promise<void> {
    historyLoading.value = true
    try {
      history.value = await api.history(limit)
      historyLoaded.value = true
      historyError.value = null
    } catch (caught) {
      historyError.value = toApiError(caught)
    } finally {
      historyLoading.value = false
    }
  }

  async function clearHistory(): Promise<void> {
    await api.clearHistory()
    history.value = []
    historyLoaded.value = true
  }

  function connect(): void {
    const ui = useUiStore()
    if (!socket) {
      socket = createEventSocket({
        onEvent: applyServerEvent,
        onStatus: (status, attempt) => {
          const previous = wsStatus.value
          wsStatus.value = status
          if (status === 'open' && previous !== 'open') {
            ui.pushToast({ kind: 'success', key: 'toast.wsConnected', durationMs: 2200 })
          } else if (status === 'reconnecting' && previous !== 'reconnecting' && attempt > 1) {
            ui.pushToast({ kind: 'warning', key: 'toast.wsReconnecting', durationMs: 2600 })
          }
        },
      })
    }
    socket.start()
  }

  function disconnect(): void {
    socket?.stop()
    socket = null
    wsStatus.value = 'closed'
  }

  function reset(): void {
    tasks.value = []
    loaded.value = false
    error.value = null
    history.value = []
    historyLoaded.value = false
  }

  return {
    tasks,
    loading,
    loaded,
    error,
    wsStatus,
    history,
    historyLoading,
    historyLoaded,
    historyError,
    activeCount,
    finishedCount,
    totalSpeed,
    isEmpty,
    taskById,
    applyServerEvent,
    load,
    refresh,
    create,
    start,
    pause,
    resume,
    remove,
    setConnections,
    loadHistory,
    clearHistory,
    connect,
    disconnect,
    reset,
  }
})
