import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, toApiError } from '../api/client'
import type { ServerSettings } from '../api/types'
import type { ApiError } from '../api/client'

export const FALLBACK_SETTINGS: ServerSettings = {
  downloadRoot: '/downloads',
  defaultFolder: '/downloads',
  defaultConnections: 8,
  maxConcurrentDownloads: 3,
  globalSpeedLimit: 0,
  resumeOnStartup: true,
  progressIntervalMs: 500,
  authMode: 'none',
  maxConnections: 256,
}

export const useSettingsStore = defineStore('settings', () => {
  const settings = ref<ServerSettings | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const loaded = ref(false)
  const error = ref<ApiError | null>(null)

  /** Upper bound offered by the connection stepper. */
  const maxConnections = computed(() => settings.value?.maxConnections ?? FALLBACK_SETTINGS.maxConnections)
  const defaultConnections = computed(
    () => settings.value?.defaultConnections ?? FALLBACK_SETTINGS.defaultConnections,
  )
  const defaultFolder = computed(() => settings.value?.defaultFolder ?? FALLBACK_SETTINGS.defaultFolder)

  async function load(force = false): Promise<ServerSettings | null> {
    if (loaded.value && !force) return settings.value
    loading.value = true
    error.value = null
    try {
      settings.value = await api.getSettings()
      loaded.value = true
      return settings.value
    } catch (caught) {
      error.value = toApiError(caught)
      return null
    } finally {
      loading.value = false
    }
  }

  async function save(patch: Partial<ServerSettings>): Promise<ServerSettings | null> {
    saving.value = true
    try {
      const next = await api.updateSettings(patch)
      settings.value = next
      loaded.value = true
      error.value = null
      return next
    } catch (caught) {
      error.value = toApiError(caught)
      throw caught
    } finally {
      saving.value = false
    }
  }

  function reset(): void {
    settings.value = null
    loaded.value = false
    error.value = null
  }

  return {
    settings,
    loading,
    saving,
    loaded,
    error,
    maxConnections,
    defaultConnections,
    defaultFolder,
    load,
    save,
    reset,
  }
})
