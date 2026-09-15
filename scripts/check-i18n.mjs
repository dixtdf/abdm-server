#!/usr/bin/env node
/**
 * i18n catalogue check.
 *
 * Reads every `*.json` under `web/src/locales`, flattens the keys and fails on:
 *   1. invalid JSON
 *   2. missing keys vs the default locale (`en-US`)
 *   3. extra / orphan keys (present in a locale but not in `en-US`)
 *   4. empty values
 *   5. placeholder mismatch (`{name}` sets must be identical for a given key)
 *   6. an error code from the catalogue in `docs/api.md` without an
 *      `errors.<CODE>` key in every locale (and vice versa, reported as a
 *      failure when an `errors.*` key is not part of the catalogue)
 *
 * Plain node ESM, no dependencies. Runnable as
 *   node scripts/check-i18n.mjs            (from the repository root)
 *   node ../scripts/check-i18n.mjs         (via `npm run i18n:check` in web/)
 * and importable from vitest:
 *   import { checkI18n, parseErrorCodes, flatten } from '../../scripts/check-i18n.mjs'
 */

import { readdirSync, readFileSync, statSync } from 'node:fs'
import { basename, dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const DEFAULT_LOCALE = 'en-US'

/** Repository root = the parent of this script's directory. */
export function repoRootFrom(importMetaUrl) {
  return resolve(dirname(fileURLToPath(importMetaUrl)), '..')
}

/* ------------------------------------------------------------------ helpers */

/** Flattens `{ a: { b: 'x' } }` into `{ 'a.b': 'x' }`, reporting non-string leaves. */
export function flatten(input, prefix = '') {
  const messages = new Map()
  const invalid = []

  const walk = (node, path) => {
    if (node !== null && typeof node === 'object' && !Array.isArray(node)) {
      for (const [key, value] of Object.entries(node)) walk(value, path ? `${path}.${key}` : key)
      return
    }
    if (typeof node === 'string') {
      messages.set(path, node)
      return
    }
    invalid.push(path || '(root)')
  }

  walk(input, prefix)
  return { messages, invalid }
}

/** Distinct `{name}` placeholders of a message, sorted. */
export function placeholders(message) {
  const found = new Set()
  const pattern = /\{([a-zA-Z0-9_]+)\}/g
  let match = pattern.exec(message)
  while (match !== null) {
    found.add(match[1])
    match = pattern.exec(message)
  }
  return [...found].sort()
}

/** Sorted list of `*.json` files inside a directory. */
export function listLocaleFiles(localesDir) {
  return readdirSync(localesDir)
    .filter((name) => name.endsWith('.json'))
    .sort()
    .map((name) => join(localesDir, name))
}

/**
 * Parses the error code catalogue out of `docs/api.md`.
 * The catalogue lives in the fenced code block of the `## Error code catalogue`
 * section; codes are upper snake case tokens.
 */
export function parseErrorCodes(apiMarkdown) {
  const section = apiMarkdown.split(/^##\s+Error code catalogue\s*$/m)[1]
  if (!section) return []
  const block = section.match(/```[a-z]*\n([\s\S]*?)```/)
  const body = block ? block[1] : section
  const codes = (body.match(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*\b/g) ?? []).filter(
    (token) => token.length >= 3,
  )
  return [...new Set(codes)].sort()
}

/* -------------------------------------------------------------- core check */

/**
 * @param {{ repoRoot?: string, localesDir?: string, defaultLocale?: string, log?: boolean }} [options]
 * @returns {{ ok: boolean, errors: string[], warnings: string[], report: string }}
 */
export function checkI18n(options = {}) {
  const repoRoot = options.repoRoot ?? repoRootFrom(import.meta.url)
  const localesDir = options.localesDir ?? join(repoRoot, 'web', 'src', 'locales')
  const defaultLocale = options.defaultLocale ?? DEFAULT_LOCALE
  const apiPath = join(repoRoot, 'docs', 'api.md')

  const errors = []
  const warnings = []
  const lines = []

  const files = listLocaleFiles(localesDir)
  if (files.length === 0) {
    errors.push(`no locale files found in ${localesDir}`)
  }

  /** locale -> Map(key -> message) */
  const catalogues = new Map()
  const invalidLeaves = []

  for (const file of files) {
    const locale = basename(file).replace(/\.json$/, '')
    const label = relative(repoRoot, file).replace(/\\/g, '/')
    let parsed
    try {
      parsed = JSON.parse(readFileSync(file, 'utf8'))
    } catch (error) {
      errors.push(`invalid JSON in ${label}: ${error.message}`)
      continue
    }
    const { messages, invalid } = flatten(parsed)
    for (const path of invalid) invalidLeaves.push(`${label}: ${path}`)
    for (const [key, value] of messages) {
      if (value.trim() === '') errors.push(`${label}: empty value for "${key}"`)
    }
    catalogues.set(locale, messages)
  }

  for (const entry of invalidLeaves) {
    errors.push(`non-string translation value: ${entry}`)
  }

  const base =
    catalogues.get(defaultLocale) ?? new Map()
  if (!catalogues.has(defaultLocale)) {
    errors.push(`default locale "${defaultLocale}" is missing from ${localesDir}`)
  }

  const localeNames = [...catalogues.keys()].sort()
  for (const locale of localeNames) {
    if (locale === defaultLocale) continue
    const messages = catalogues.get(locale)
    const missing = []
    const extra = []
    for (const key of base.keys()) {
      if (!messages.has(key)) missing.push(key)
    }
    for (const key of messages.keys()) {
      if (!base.has(key)) extra.push(key)
    }
    for (const key of missing.sort()) {
      errors.push(`${locale}: missing key "${key}"`)
    }
    for (const key of extra.sort()) {
      errors.push(`${locale}: extra key "${key}" (not in ${defaultLocale})`)
    }
  }

  // Placeholder parity across every locale, for every key of the default locale.
  for (const [key, message] of base) {
    const expected = placeholders(message).join(',')
    for (const locale of localeNames) {
      if (locale === defaultLocale) continue
      const other = catalogues.get(locale).get(key)
      if (other === undefined) continue
      const actual = placeholders(other).join(',')
      if (actual !== expected) {
        errors.push(
          `${locale}: placeholder mismatch for "${key}" (${defaultLocale}: {${expected || 'none'}}, ${locale}: {${actual || 'none'}})`,
        )
      }
    }
  }

  // Error catalogue from docs/api.md must exist as errors.<CODE> in every locale.
  let codes = []
  try {
    codes = parseErrorCodes(readFileSync(apiPath, 'utf8'))
  } catch (error) {
    errors.push(`could not read ${relative(repoRoot, apiPath)}: ${error.message}`)
  }
  if (codes.length === 0) {
    errors.push(`no error codes parsed out of ${relative(repoRoot, apiPath)}`)
  }
  for (const locale of localeNames) {
    const messages = catalogues.get(locale)
    for (const code of codes) {
      if (!messages.has(`errors.${code}`)) {
        errors.push(`${locale}: missing key "errors.${code}" for the catalogue code ${code}`)
      }
    }
    for (const key of [...messages.keys()].filter((k) => k.startsWith('errors.')).sort()) {
      if (!codes.includes(key.slice('errors.'.length))) {
        errors.push(`${locale}: "errors.${key.slice('errors.'.length)}" is not in the docs/api.md catalogue`)
      }
    }
  }

  lines.push('i18n check')
  lines.push(`  locales   : ${localeNames.join(', ')} (default ${defaultLocale})`)
  lines.push(`  keys      : ${base.size} in ${defaultLocale}`)
  lines.push(`  catalogue : ${codes.length} error codes in ${localeNames.length} locale(s)`)
  lines.push(`  placeholders: verified across ${localeNames.length} locale(s)`)

  if (errors.length > 0) {
    lines.push(`  FAILED    : ${errors.length} problem(s)`)
    for (const item of errors) lines.push(`    - ${item}`)
  } else {
    lines.push('  OK        : no missing, extra, empty or mismatched keys')
  }
  if (warnings.length > 0) {
    for (const item of warnings) lines.push(`    ! ${item}`)
  }

  return { ok: errors.length === 0, errors, warnings, report: lines.join('\n') }
}

/* ------------------------------------------------------------------- cli */

const invokedDirectly =
  process.argv[1] !== undefined &&
  resolve(process.argv[1]).toLowerCase() === fileURLToPath(import.meta.url).toLowerCase()

if (invokedDirectly) {
  const result = checkI18n()
  if (result.ok) {
    process.stdout.write(`${result.report}\n`)
  } else {
    process.stderr.write(`${result.report}\n`)
  }
  process.exit(result.ok ? 0 : 1)
}
