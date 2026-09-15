import { createI18n } from 'vue-i18n'
import enUS from '../locales/en-US.json'
import zhCN from '../locales/zh-CN.json'
import { FALLBACK_LOCALE } from './helpers'
import { STORAGE_KEYS, readStorage, writeStorage } from '../utils/storage'

export const SUPPORTED_LOCALES = ['en-US', 'zh-CN'] as const
export type Locale = (typeof SUPPORTED_LOCALES)[number]
/** `system` resolves through `navigator.language`. */
export type LocalePreference = 'system' | Locale

/**
 * Shape of the catalogues: every top level key is an object, leaves are strings
 * and the deepest message is a string (`download.status.DOWNLOADING`). That is
 * exactly assignable to vue-i18n's `LocaleMessage` record without letting
 * inference explode.
 */
export type Catalogue = Record<string, Record<string, string | Record<string, string>>>

export const messages: Record<Locale, Catalogue> = {
  'en-US': enUS as Catalogue,
  'zh-CN': zhCN as Catalogue,
}
/** `zh*` -> zh-CN, everything else -> en-US. */
export function detectLocale(language: string | undefined | null): Locale {
  return typeof language === 'string' && /^zh/i.test(language) ? 'zh-CN' : FALLBACK_LOCALE
}

export function readLocalePreference(): LocalePreference {
  const stored = readStorage(STORAGE_KEYS.locale)
  if (stored === 'system' || stored === null) return 'system'
  return (SUPPORTED_LOCALES as readonly string[]).includes(stored) ? (stored as Locale) : 'system'
}

export function resolveLocale(preference: LocalePreference, language?: string): Locale {
  if (preference !== 'system') return preference
  const nav = language ?? (typeof navigator === 'undefined' ? '' : navigator.language)
  return detectLocale(nav)
}

export function applyLocale(preference: LocalePreference): Locale {
  const locale = resolveLocale(preference)
  // With `legacy: false` the global locale is a writable ref at runtime, which
  // the public `I18n['global']` union type does not expose.
  const composer = i18n.global as unknown as { locale: { value: Locale } }
  composer.locale.value = locale
  if (typeof document !== 'undefined') {
    document.documentElement.lang = locale
  }
  return locale
}

export function setLocalePreference(preference: LocalePreference): Locale {
  writeStorage(STORAGE_KEYS.locale, preference)
  return applyLocale(preference)
}

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: resolveLocale(readLocalePreference()),
  fallbackLocale: FALLBACK_LOCALE,
  // The JSON catalogues are typed as a bounded nested record; vue-i18n's
  // `LocaleMessage` cannot be inferred from an `unknown`-valued record.
  messages: messages as Catalogue,
  warnHtmlMessage: false,
})

/** Translator usable outside component setup (stores, helpers, toasts). */
export function translate(key: string, params?: Record<string, unknown>): string {
  const t = i18n.global.t as unknown as (k: string, p?: Record<string, unknown>) => string
  return params === undefined ? t(key) : t(key, params)
}

/** True when the key exists in the active locale (or in the fallback locale). */
export function hasTranslation(key: string): boolean {
  const te = i18n.global.te as unknown as (k: string, locale?: string) => boolean
  return te(key) || te(key, FALLBACK_LOCALE)
}
