import { describe, expect, it } from 'vitest'
import {
  formatBytes,
  formatDateTime,
  formatDuration,
  formatNumber,
  formatPercent,
  formatSpeed,
} from '../src/utils/format'

describe('formatBytes', () => {
  it('uses binary units by default', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1024)).toBe('1 KiB')
    expect(formatBytes(1536)).toBe('1.5 KiB')
    expect(formatBytes(4294967296)).toBe('4 GiB')
  })

  it('uses decimal units when asked', () => {
    expect(formatBytes(1000, { units: 'decimal' })).toBe('1 KB')
    expect(formatBytes(1500, { units: 'decimal' })).toBe('1.5 KB')
    expect(formatBytes(1000000, { units: 'decimal' })).toBe('1 MB')
  })

  it('formats through Intl for the active locale', () => {
    expect(formatBytes(1234567, { locale: 'en-US' })).toBe('1.2 MiB')
    expect(formatBytes(1234567, { locale: 'zh-CN' })).toBe('1.2 MiB')
  })

  it('renders the unknown label for negative values (-1 means unknown)', () => {
    expect(formatBytes(-1, { unknown: 'Unknown' })).toBe('Unknown')
    expect(formatBytes(-1)).toBe('—')
  })
})

describe('formatSpeed', () => {
  it('appends the per second suffix and keeps the unit system', () => {
    expect(formatSpeed(1048576)).toBe('1 MiB/s')
    expect(formatSpeed(1048576, { units: 'decimal' })).toBe('1 MB/s')
    expect(formatSpeed(0)).toBe('0 B/s')
  })

  it('renders the unknown label for a negative speed', () => {
    expect(formatSpeed(-1, { unknown: 'Unknown' })).toBe('Unknown')
  })
})

describe('formatDuration', () => {
  it('formats seconds, minutes and hours through Intl unit formatting', () => {
    expect(formatDuration(48, { locale: 'en-US' })).toBe('48s')
    expect(formatDuration(90, { locale: 'en-US' })).toBe('1m 30s')
    expect(formatDuration(3690, { locale: 'en-US' })).toBe('1h 1m 30s')
  })

  it('follows the locale and returns the unknown label for -1', () => {
    expect(formatDuration(65, { locale: 'zh-CN' })).not.toBe(formatDuration(65, { locale: 'en-US' }))
    expect(formatDuration(-1, { unknown: 'n/a' })).toBe('n/a')
  })
})

describe('formatDateTime', () => {
  it('formats absolute timestamps with the locale', () => {
    const value = new Date(2026, 0, 2, 3, 4, 5).getTime()
    expect(formatDateTime(value, { locale: 'en-US' })).toContain('2026')
    expect(formatDateTime(value, { locale: 'zh-CN' })).toContain('2026')
  })

  it('handles missing and zero timestamps', () => {
    expect(formatDateTime(null, { unknown: '—' })).toBe('—')
    expect(formatDateTime(undefined, { unknown: '—' })).toBe('—')
    expect(formatDateTime(0, { unknown: '—' })).toBe('—')
  })
})

describe('formatPercent and formatNumber', () => {
  it('treats values above one as percentages', () => {
    expect(formatPercent(0.5)).toBe('50%')
    expect(formatPercent(50)).toBe('50%')
    expect(formatPercent(0.1234)).toBe('12.3%')
  })

  it('formats plain numbers and guards against NaN', () => {
    expect(formatNumber(1234567)).toBe('1,234,567')
    expect(formatNumber(Number.NaN)).toBe('—')
  })
})
