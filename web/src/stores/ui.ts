import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { applyLocale } from '../i18n'
import { errorMessageKey, isApiError } from '../api/client'
import { STORAGE_KEYS, readStorage, writeStorage } from '../utils/storage'
import type { LocalePreference } from '../i18n'
import type { UnitSystem } from '../utils/format'

export type ThemePreference = 'system' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'
export type ToastKind = 'info' | 'success' | 'warning' | 'error'

export interface Toast {
  id: string
  kind: ToastKind
  /** i18n key, e.g. `errors.TASK_NOT_FOUND` or `toast.downloadAdded`. */
  key: string
  params?: Record<string, unknown>
  /** Developer detail from the server envelope, never the primary message. */
  detail?: string | null
}

export interface ToastInput {
  kind?: ToastKind
  key: string
  params?: Record<string, unknown>
  detail?: string | null
  /** Milliseconds, `0` keeps the toast until it is dismissed. */
  durationMs?: number
}

const DEFAULT_TOAST_MS = 4200
const ERROR_TOAST_MS = 8000

let toastSeq = 0

function readTheme(): ThemePreference {
  const stored = readStorage(STORAGE_KEYS.theme)
  return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system'
}

function readUnits(): UnitSystem {
  const stored = readStorage(STORAGE_KEYS.units)
  return stored === 'decimal' ? 'decimal' : 'binary'
}

function themeMedia(): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null
  return window.matchMedia('(prefers-color-scheme: dark)')
}

export const useUiStore = defineStore('ui', () => {
  const media = themeMedia()

  const theme = ref<ThemePreference>(readTheme())
  const resolvedTheme = ref<ResolvedTheme>('light')
  const locale = ref<LocalePreference>(readLocalePreference())
  const units = ref<UnitSystem>(readUnits())

  // Shell state
  const sidebarOpen = ref(false)
  const addDownloadOpen = ref(false)
  const selectedTaskId = ref<string | null>(null)
  const toasts = ref<Toast[]>([])

  function readLocalePreference(): LocalePreference {
    const stored = readStorage(STORAGE_KEYS.locale)
    if (stored === 'en-US' || stored === 'zh-CN') return stored
    return 'system'
  }

  function resolveTheme(preference: ThemePreference): ResolvedTheme {
    if (preference === 'system') return media?.matches ? 'dark' : 'light'
    return preference
  }

  function applyTheme(): void {
    resolvedTheme.value = resolveTheme(theme.value)
    if (typeof document !== 'undefined') {
      document.documentElement.dataset.theme = resolvedTheme.value
    }
  }

  function setTheme(preference: ThemePreference): void {
    theme.value = preference
    writeStorage(STORAGE_KEYS.theme, preference)
    applyTheme()
  }

  function setLocale(preference: LocalePreference): void {
    locale.value = preference
    applyLocale(preference)
  }

  function setUnits(system: UnitSystem): void {
    units.value = system
    writeStorage(STORAGE_KEYS.units, system)
  }

  // --- shell ------------------------------------------------------------
  function openSidebar(): void {
    sidebarOpen.value = true
  }

  function closeSidebar(): void {
    sidebarOpen.value = false
  }

  function toggleSidebar(): void {
    sidebarOpen.value = !sidebarOpen.value
  }

  function openAddDownload(): void {
    addDownloadOpen.value = true
  }

  function closeAddDownload(): void {
    addDownloadOpen.value = false
  }

  function selectTask(id: string | null): void {
    selectedTaskId.value = id
  }

  // --- toasts -----------------------------------------------------------
  function dismissToast(id: string): void {
    toasts.value = toasts.value.filter((toast) => toast.id !== id)
  }

  function clearToasts(): void {
    toasts.value = []
  }

  function pushToast(input: ToastInput): string {
    toastSeq += 1
    const id = `toast-${Date.now()}-${toastSeq}`
    toasts.value = [...toasts.value, {
      id,
      kind: input.kind ?? 'info',
      key: input.key,
      params: input.params,
      detail: input.detail ?? null,
    }]
    const duration = input.durationMs ?? (input.kind === 'error' ? ERROR_TOAST_MS : DEFAULT_TOAST_MS)
    if (duration > 0 && typeof window !== 'undefined') {
      window.setTimeout(() => dismissToast(id), duration)
    }
    return id
  }

  /** Maps an `ApiError` (or anything else) onto an `errors.<CODE>` toast. */
  function pushError(error: unknown, fallbackKey = 'toast.operationFailed'): void {
    if (isApiError(error)) {
      pushToast({
        kind: 'error',
        key: errorMessageKey(error.code),
        params: error.params,
        detail: error.detail,
      })
      return
    }
    pushToast({
      kind: 'error',
      key: fallbackKey,
      detail: error instanceof Error ? error.message : null,
    })
  }

  if (media && typeof media.addEventListener === 'function') {
    media.addEventListener('change', () => {
      if (theme.value === 'system') applyTheme()
    })
  }

  applyTheme()
  applyLocale(locale.value)

  watch(theme, () => applyTheme())
  watch(locale, (value) => writeStorage(STORAGE_KEYS.locale, value))

  const isDark = computed(() => resolvedTheme.value === 'dark')
  const hasToasts = computed(() => toasts.value.length > 0)

  return {
    theme,
    resolvedTheme,
    isDark,
    locale,
    units,
    sidebarOpen,
    addDownloadOpen,
    selectedTaskId,
    toasts,
    hasToasts,
    setTheme,
    setLocale,
    setUnits,
    openSidebar,
    closeSidebar,
    toggleSidebar,
    openAddDownload,
    closeAddDownload,
    selectTask,
    pushToast,
    pushError,
    dismissToast,
    clearToasts,
    applyTheme,
  }
})
