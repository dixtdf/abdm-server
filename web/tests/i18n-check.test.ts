import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

/**
 * `scripts/check-i18n.mjs` is plain node ESM shared with CI, so it is loaded
 * through `createRequire` (Node >= 22 can require ESM) to keep it dependency
 * free and still unit testable.
 */
interface CheckResult {
  ok: boolean
  errors: string[]
  warnings: string[]
  report: string
}

interface CheckerModule {
  checkI18n(options?: { repoRoot?: string; localesDir?: string; defaultLocale?: string }): CheckResult
  parseErrorCodes(markdown: string): string[]
  flatten(input: Record<string, unknown>): {
    messages: Map<string, string>
    invalid: string[]
  }
  placeholders(message: string): string[]
}

const require = createRequire(import.meta.url)
const checker = require('../../scripts/check-i18n.mjs') as CheckerModule

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const realApiMarkdown = () => {
  const { readFileSync } = require('node:fs') as typeof import('node:fs')
  return readFileSync(join(repoRoot, 'docs', 'api.md'), 'utf8')
}

const CODES = checker.parseErrorCodes(realApiMarkdown())

function catalogue(): Record<string, unknown> {
  return {
    common: {
      appName: 'Download Manager',
      count: '{count} downloads',
    },
    errors: Object.fromEntries(CODES.map((code) => [code, `Error ${code}`])),
  }
}

function write(localesDir: string, locale: string, data: unknown | string): void {
  const content = typeof data === 'string' ? data : `${JSON.stringify(data, null, 2)}\n`
  writeFileSync(join(localesDir, `${locale}.json`), content, 'utf8')
}

/** Builds a throwaway repository root with its own locales and docs/api.md. */
function fixture(mutate?: (dir: string) => void): string {
  const root = mkdtempSync(join(tmpdir(), 'i18n-check-'))
  mkdirSync(join(root, 'docs'), { recursive: true })
  copyFileSync(join(repoRoot, 'docs', 'api.md'), join(root, 'docs', 'api.md'))
  const localesDir = join(root, 'web', 'src', 'locales')
  mkdirSync(localesDir, { recursive: true })
  write(localesDir, 'en-US', catalogue())
  write(localesDir, 'zh-CN', catalogue())
  mutate?.(localesDir)
  return root
}

const roots: string[] = []
function track(root: string): string {
  roots.push(root)
  return root
}

afterAll(() => {
  for (const root of roots) rmSync(root, { recursive: true, force: true })
})

describe('flat message helpers', () => {
  it('flattens nested catalogues and reports non strings', () => {
    const { messages, invalid } = checker.flatten({ a: { b: 'x' }, c: 3, d: { e: true } })
    expect(messages.get('a.b')).toBe('x')
    expect(invalid.sort()).toEqual(['c', 'd.e'])
  })

  it('extracts sorted, distinct placeholders', () => {
    expect(checker.placeholders('{count} of {name} in {name}')).toEqual(['count', 'name'])
    expect(checker.placeholders('no placeholders here')).toEqual([])
  })
})

describe('error code catalogue parsing', () => {
  it('reads every code out of docs/api.md', () => {
    expect(CODES).toHaveLength(15)
    expect(CODES).toContain('INVALID_URL')
    expect(CODES).toContain('NETWORK')
    expect(CODES).toContain('UNAUTHORIZED')
    expect(CODES).toContain('ENGINE_UNAVAILABLE')
    expect(CODES).not.toContain('DOWNLOAD_STATES')
  })
})

describe('check-i18n on the real catalogues', () => {
  it('passes for web/src/locales', () => {
    const result = checker.checkI18n({ repoRoot })
    expect(result.errors).toEqual([])
    expect(result.ok).toBe(true)
    expect(result.report).toContain('i18n check')
  })
})

describe('check-i18n failure modes', () => {
  it('accepts two identical catalogues', () => {
    const root = track(fixture())
    expect(checker.checkI18n({ repoRoot: root }).ok).toBe(true)
  })

  it('fails on invalid JSON', () => {
    const root = track(fixture((dir) => write(dir, 'zh-CN', '{ "common": ')))
    const result = checker.checkI18n({ repoRoot: root })
    expect(result.ok).toBe(false)
    expect(result.errors.join('\n')).toContain('invalid JSON in web/src/locales/zh-CN.json')
  })

  it('fails on a missing key', () => {
    const root = track(
      fixture((dir) => {
        const zh = catalogue()
        delete (zh.common as Record<string, unknown>).appName
        write(dir, 'zh-CN', zh)
      }),
    )
    const result = checker.checkI18n({ repoRoot: root })
    expect(result.ok).toBe(false)
    expect(result.errors).toContain('zh-CN: missing key "common.appName"')
  })

  it('fails on an extra key', () => {
    const root = track(
      fixture((dir) => {
        const zh = catalogue()
        ;(zh.common as Record<string, unknown>).orphan = 'nope'
        write(dir, 'zh-CN', zh)
      }),
    )
    const result = checker.checkI18n({ repoRoot: root })
    expect(result.ok).toBe(false)
    expect(result.errors).toContain('zh-CN: extra key "common.orphan" (not in en-US)')
  })

  it('fails on an empty value', () => {
    const root = track(
      fixture((dir) => {
        const zh = catalogue()
        ;(zh.common as Record<string, unknown>).appName = '   '
        write(dir, 'zh-CN', zh)
      }),
    )
    const result = checker.checkI18n({ repoRoot: root })
    expect(result.ok).toBe(false)
    expect(result.errors).toContain('web/src/locales/zh-CN.json: empty value for "common.appName"')
  })

  it('fails when the placeholder sets differ', () => {
    const root = track(
      fixture((dir) => {
        const zh = catalogue()
        ;(zh.common as Record<string, unknown>).count = '{total} downloads'
        write(dir, 'zh-CN', zh)
      }),
    )
    const result = checker.checkI18n({ repoRoot: root })
    expect(result.ok).toBe(false)
    expect(result.errors.join('\n')).toContain('placeholder mismatch for "common.count"')
  })

  it('fails when a catalogue error code has no translation', () => {
    const root = track(
      fixture((dir) => {
        const zh = catalogue()
        delete (zh.errors as Record<string, unknown>).DISK_FULL
        write(dir, 'zh-CN', zh)
      }),
    )
    const result = checker.checkI18n({ repoRoot: root })
    expect(result.ok).toBe(false)
    expect(result.errors).toContain(
      'zh-CN: missing key "errors.DISK_FULL" for the catalogue code DISK_FULL',
    )
  })

  it('fails on an error key that is not part of the catalogue', () => {
    const root = track(
      fixture((dir) => {
        const zh = catalogue()
        ;(zh.errors as Record<string, unknown>).MADE_UP = 'nope'
        write(dir, 'zh-CN', zh)
      }),
    )
    const result = checker.checkI18n({ repoRoot: root })
    expect(result.ok).toBe(false)
    expect(result.errors.join('\n')).toContain(
      '"errors.MADE_UP" is not in the docs/api.md catalogue',
    )
  })
})

beforeAll(() => {
  expect(CODES.length).toBeGreaterThan(0)
})
