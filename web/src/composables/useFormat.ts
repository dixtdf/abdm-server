import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUiStore } from '../stores/ui'
import {
  formatBytes,
  formatDateTime,
  formatDuration,
  formatPercent,
  formatSpeed,
  type FormatOptions,
} from '../utils/format'

/**
 * Locale + unit aware formatters for templates. Every component goes through
 * this so sizes, speeds and dates can never be concatenated by hand.
 */
export function useFormat() {
  const ui = useUiStore()
  const { t, locale } = useI18n()

  const options = computed<FormatOptions>(() => ({
    units: ui.units,
    locale: locale.value,
    unknown: t('common.unknown'),
  }))

  return {
    options,
    bytes: (value: number) => formatBytes(value, options.value),
    speed: (value: number) => formatSpeed(value, options.value),
    duration: (value: number) => formatDuration(value, options.value),
    dateTime: (value: number | null | undefined) => formatDateTime(value, options.value),
    percent: (value: number) => formatPercent(value, options.value),
  }
}
