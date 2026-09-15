/**
 * Locale aware formatting. Dates, numbers and sizes always go through `Intl`
 * so we never concatenate digits with units by hand.
 */

export type UnitSystem = 'binary' | 'decimal'

export interface FormatOptions {
  units?: UnitSystem
  locale?: string
  /** Shown when a value is unknown (`total === -1`). */
  unknown?: string
  /** Maximum fraction digits for byte/speed values. */
  digits?: number
}

const UNITS: Record<UnitSystem, { base: number; suffixes: string[] }> = {
  binary: { base: 1024, suffixes: ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'] },
  decimal: { base: 1000, suffixes: ['B', 'KB', 'MB', 'GB', 'TB', 'PB'] },
}

export const UNKNOWN_PLACEHOLDER = '—'

function numberFormat(locale: string, digits: number): Intl.NumberFormat {
  return new Intl.NumberFormat(locale, {
    minimumFractionDigits: 0,
    maximumFractionDigits: digits,
  })
}

export function formatNumber(
  value: number,
  { locale = 'en-US', digits = 0 }: { locale?: string; digits?: number } = {},
): string {
  if (!Number.isFinite(value)) return UNKNOWN_PLACEHOLDER
  return numberFormat(locale, digits).format(value)
}

/** Splits a byte count into a scaled value plus its unit label. */
function scaleBytes(
  bytes: number,
  system: UnitSystem,
): { value: number; suffix: string } {
  const { base, suffixes } = UNITS[system]
  const absolute = Math.abs(bytes)
  let exponent = 0
  while (absolute >= base ** (exponent + 1) && exponent < suffixes.length - 1) {
    exponent += 1
  }
  return { value: bytes / base ** exponent, suffix: suffixes[exponent] as string }
}

export function formatBytes(bytes: number, options: FormatOptions = {}): string {
  const { units = 'binary', locale = 'en-US', unknown = UNKNOWN_PLACEHOLDER, digits = 1 } = options
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return unknown
  const { value, suffix } = scaleBytes(bytes, units)
  const isInteger = value >= 100 || Number.isInteger(value)
  const text = numberFormat(locale, isInteger ? 0 : digits).format(value)
  return `${text} ${suffix}`
}

/** `MiB/s` (binary) or `MB/s` (decimal). */
export function formatSpeed(bytesPerSecond: number, options: FormatOptions = {}): string {
  const { unknown = UNKNOWN_PLACEHOLDER } = options
  if (bytesPerSecond === null || bytesPerSecond === undefined || bytesPerSecond < 0) return unknown
  return `${formatBytes(bytesPerSecond, { ...options, unknown: '0 B' })}/s`
}

/**
 * Durations via `Intl.NumberFormat` unit formatting, e.g. `1h 5m`, `12s`.
 * Unknown ETAs (`-1`) return the `unknown` label.
 */
export function formatDuration(seconds: number, options: FormatOptions = {}): string {
  const { locale = 'en-US', unknown = UNKNOWN_PLACEHOLDER } = options
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds) || seconds < 0) {
    return unknown
  }
  const total = Math.floor(seconds)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const secs = total % 60

  const unit = (value: number, name: string): string =>
    new Intl.NumberFormat(locale, {
      style: 'unit',
      unit: name,
      unitDisplay: 'narrow',
      maximumFractionDigits: 0,
    }).format(value)

  const parts: string[] = []
  if (hours > 0) parts.push(unit(hours, 'hour'))
  if (hours > 0 || minutes > 0) parts.push(unit(minutes, 'minute'))
  parts.push(unit(secs, 'second'))
  return parts.join(' ')
}

export function formatDateTime(
  value: number | null | undefined,
  { locale = 'en-US', unknown = UNKNOWN_PLACEHOLDER }: FormatOptions = {},
): string {
  if (value === null || value === undefined || !Number.isFinite(value) || value <= 0) return unknown
  return new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(new Date(value))
}

/** Ratio (`0..1`) or already-`0..100` percentage. */
export function formatPercent(value: number, options: FormatOptions = {}): string {
  const { locale = 'en-US', unknown = UNKNOWN_PLACEHOLDER, digits = 1 } = options
  if (value === null || value === undefined || !Number.isFinite(value)) return unknown
  const ratio = value > 1 ? value / 100 : value
  return new Intl.NumberFormat(locale, {
    style: 'percent',
    maximumFractionDigits: digits,
  }).format(ratio)
}

/** Convenience re-export so consumers only ever import from `utils/format`. */
export {
  extractPlaceholders,
  flattenMessages,
  interpolateMessage,
  FALLBACK_LOCALE,
  type FlatMessages,
  type Messages,
} from '../i18n/helpers'
