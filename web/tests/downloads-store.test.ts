import { describe, expect, it, vi } from 'vitest'
import { applyEvent } from '../src/stores/downloads'
import type { ServerEvent, Task } from '../src/api/types'

function makeTask(overrides: Partial<Task> = {}): Task {
  return {
    id: 'task-1',
    url: 'https://example.com/file.iso',
    fileName: 'file.iso',
    folder: '/downloads',
    path: '/downloads/file.iso',
    state: 'DOWNLOADING',
    downloaded: 100,
    total: 1000,
    progress: 0.1,
    speed: 1024,
    averageSpeed: 900,
    connections: 8,
    activeConnections: 7,
    etaSeconds: 30,
    supportsRange: true,
    hls: false,
    speedLimit: 0,
    checksum: null,
    error: null,
    createdAt: 1,
    startedAt: 2,
    completedAt: null,
    queuePosition: null,
    ...overrides,
  }
}

describe('downloads store reducer', () => {
  it('seeds the whole list from the hello frame', () => {
    const tasks = [makeTask({ id: 'a' }), makeTask({ id: 'b' })]
    const event: ServerEvent = {
      type: 'hello',
      server: { version: '0.1.0', engine: 'native', apiVersion: 'v1' },
      tasks,
    }
    expect(applyEvent([], event)).toEqual(tasks)
  })

  it('prepends a newly added download', () => {
    const existing = [makeTask({ id: 'a' })]
    const added = makeTask({ id: 'b' })
    const next = applyEvent(existing, { type: 'download.added', task: added })
    expect(next.map((task) => task.id)).toEqual(['b', 'a'])
    // the previous state is never mutated
    expect(existing.map((task) => task.id)).toEqual(['a'])
  })

  it('upserts instead of duplicating when the id already exists', () => {
    const existing = [makeTask({ id: 'a', fileName: 'old.iso' })]
    const next = applyEvent(existing, {
      type: 'download.added',
      task: makeTask({ id: 'a', fileName: 'new.iso' }),
    })
    expect(next).toHaveLength(1)
    expect(next[0]?.fileName).toBe('new.iso')
  })

  it('merges a progress frame into the matching task, deriving the ratio', () => {
    const existing = [makeTask({ id: 'a', downloaded: 100, total: 1000, progress: 0.1 })]
    const next = applyEvent(existing, {
      type: 'download.progress',
      taskId: 'a',
      progress: {
        downloaded: 500,
        total: 1000,
        speed: 2048,
        averageSpeed: 1500,
        connections: 16,
        activeConnections: 15,
        etaSeconds: 12,
        state: 'DOWNLOADING',
      },
    })
    expect(next[0]).toMatchObject({
      downloaded: 500,
      speed: 2048,
      connections: 16,
      activeConnections: 15,
      etaSeconds: 12,
      progress: 0.5,
    })
  })

  it('ignores progress for an unknown task and never touches the REST API', () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)

    const existing = [makeTask({ id: 'a' })]
    const next = applyEvent(existing, {
      type: 'download.progress',
      taskId: 'missing',
      progress: {
        downloaded: 1,
        total: 2,
        speed: 3,
        averageSpeed: 3,
        connections: 4,
        activeConnections: 4,
        etaSeconds: 5,
        state: 'DOWNLOADING',
      },
    })

    expect(next).toBe(existing)
    expect(fetchSpy).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('keeps the same array reference when a progress frame changes nothing', () => {
    const existing = [makeTask({ id: 'a' })]
    const next = applyEvent(existing, {
      type: 'download.progress',
      taskId: 'a',
      progress: {
        downloaded: 100,
        total: 1000,
        speed: 1024,
        averageSpeed: 900,
        connections: 8,
        activeConnections: 7,
        etaSeconds: 30,
        state: 'DOWNLOADING',
      },
    })
    expect(next).toBe(existing)
  })

  it('replaces the task on state and connections frames', () => {
    const existing = [makeTask({ id: 'a', state: 'DOWNLOADING' })]
    const completed = makeTask({ id: 'a', state: 'COMPLETED', downloaded: 1000, progress: 1 })
    const afterState = applyEvent(existing, {
      type: 'download.state',
      task: completed,
      previous: 'DOWNLOADING',
      state: 'COMPLETED',
      error: null,
    })
    expect(afterState[0]?.state).toBe('COMPLETED')

    const afterConnections = applyEvent(afterState, {
      type: 'download.connections',
      task: { ...completed, connections: 64, activeConnections: 61 },
      requested: 64,
      active: 61,
    })
    expect(afterConnections[0]?.connections).toBe(64)
    expect(afterConnections[0]?.activeConnections).toBe(61)
  })

  it('drops a removed download and leaves the list alone otherwise', () => {
    const existing = [makeTask({ id: 'a' }), makeTask({ id: 'b' })]
    const next = applyEvent(existing, {
      type: 'download.removed',
      taskId: 'a',
      deletedFile: false,
    })
    expect(next.map((task) => task.id)).toEqual(['b'])

    const untouched = applyEvent(existing, {
      type: 'download.removed',
      taskId: 'unknown',
      deletedFile: false,
    })
    expect(untouched).toBe(existing)
  })

  it('ignores pong frames', () => {
    const existing = [makeTask()]
    expect(applyEvent(existing, { type: 'pong' })).toBe(existing)
  })
})
