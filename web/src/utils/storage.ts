/**
 * Storage helpers with a safe fallback when `localStorage` is unavailable
 * (private mode, embedded webviews, jsdom without storage).
 */

const memory = new Map<string, string>()

function storageAvailable(): boolean {
  try {
    return typeof localStorage !== 'undefined'
  } catch {
    return false
  }
}

export function readStorage(key: string): string | null {
  if (storageAvailable()) {
    try {
      const value = localStorage.getItem(key)
      if (value !== null) return value
    } catch {
      /* fall through to the in-memory mirror */
    }
  }
  return memory.has(key) ? (memory.get(key) as string) : null
}

export function writeStorage(key: string, value: string | null): void {
  if (value === null) {
    memory.delete(key)
  } else {
    memory.set(key, value)
  }
  if (storageAvailable()) {
    try {
      if (value === null) localStorage.removeItem(key)
      else localStorage.setItem(key, value)
    } catch {
      /* the in-memory mirror already holds the value */
    }
  }
}

export const STORAGE_KEYS = {
  theme: 'ui.theme',
  locale: 'ui.locale',
  units: 'ui.units',
  token: 'api.token',
} as const
