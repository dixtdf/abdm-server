import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import DownloadsPage from '../src/pages/DownloadsPage.vue'
import { api } from '../src/api/client'
import type { Task } from '../src/api/types'
import { i18n } from '../src/i18n'
import { useDownloadsStore } from '../src/stores/downloads'
import { useSettingsStore } from '../src/stores/settings'
import { useUiStore } from '../src/stores/ui'
import { STORAGE_KEYS } from '../src/utils/storage'

function task(id: string): Task {
  return {
    id, url: `https://example.com/${id}`, fileName: `${id}.zip`, folder: '/downloads',
    path: `/downloads/${id}.zip`, state: 'COMPLETED', downloaded: 100, total: 100,
    progress: 1, speed: 0, averageSpeed: 0, connections: 8, activeConnections: 0,
    parts: [], etaSeconds: -1, supportsRange: true, hls: false, speedLimit: 0,
    checksum: null, error: null, createdAt: 1, startedAt: 1, completedAt: 2,
    queuePosition: null,
  }
}

function setup() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const downloads = useDownloadsStore()
  downloads.loaded = true
  downloads.tasks = [task('a'), task('b'), task('c')]
  const settings = useSettingsStore()
  settings.loaded = true
  const ui = useUiStore()
  const wrapper = mount(DownloadsPage, { attachTo: document.body, global: { plugins: [pinia, i18n] } })
  return { wrapper, downloads, ui }
}

function pointerDown(element: Element, x: number, y: number): void {
  const event = new MouseEvent('pointerdown', { bubbles: true, button: 0, clientX: x, clientY: y })
  Object.defineProperty(event, 'pointerType', { value: 'mouse' })
  element.dispatchEvent(event)
}

function positionRows(rows: ReturnType<ReturnType<typeof setup>['wrapper']['findAll']>): void {
  rows.forEach((row, index) => {
    const top = index * 50
    vi.spyOn(row.element, 'getBoundingClientRect').mockReturnValue({
      left: 0, right: 200, top, bottom: top + 40,
    } as DOMRect)
  })
}

function selected(wrapper: ReturnType<typeof setup>['wrapper']): boolean[] {
  return wrapper.findAll('.download__select-control').map(button => button.attributes('aria-checked') === 'true')
}

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
  localStorage.removeItem(STORAGE_KEYS.downloadsView)
})

describe('download selection', () => {
  it('starts in list view, remembers card view, and bulk deletes selected records', async () => {
    const deleteRequest = vi.spyOn(api, 'deleteDownload').mockResolvedValue(undefined)
    const { wrapper, downloads } = setup()
    expect(wrapper.find('.list--list').exists()).toBe(true)

    await wrapper.find('.downloads-toolbar__views button:last-child').trigger('click')
    expect(wrapper.find('.list--card').exists()).toBe(true)
    expect(localStorage.getItem(STORAGE_KEYS.downloadsView)).toBe('card')

    await wrapper.find('.downloads-toolbar__all input').setValue(true)
    expect(selected(wrapper)).toEqual([true, true, true])
    await wrapper.findAll('.download__select-control')[2]!.trigger('click')
    expect(wrapper.text()).toContain('2 selected')

    await wrapper.find('.downloads-toolbar .btn--danger').trigger('click')
    expect(document.body.textContent).toContain('Delete the 2 selected downloads?')
    ;(document.body.querySelector('.dialog__footer .btn--danger') as HTMLButtonElement).click()
    await vi.waitFor(() => expect(deleteRequest).toHaveBeenCalledTimes(2))
    expect(deleteRequest).toHaveBeenNthCalledWith(1, 'a', false)
    expect(deleteRequest).toHaveBeenNthCalledWith(2, 'b', false)
    expect(downloads.tasks.map(item => item.id)).toEqual(['c'])
    wrapper.unmount()
  })

  it('selects a mouse-dragged range without opening details', async () => {
    const { wrapper, ui } = setup()
    const rows = wrapper.findAll('[data-task-id]')
    positionRows(rows)
    pointerDown(rows[0]!.element, 10, 10)
    window.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientX: 20, clientY: 65 }))
    window.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }))
    rows[0]!.element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    expect(selected(wrapper)).toEqual([true, true, false])
    expect(ui.selectedTaskId).toBeNull()
    wrapper.unmount()
  })

  it('draws a marquee from blank space and selects intersecting rows', async () => {
    const { wrapper, ui } = setup()
    const rows = wrapper.findAll('[data-task-id]')
    positionRows(rows)
    pointerDown(wrapper.find('.selection-surface').element, 250, 145)
    window.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientX: 100, clientY: 55 }))
    await nextTick()
    expect(wrapper.find('.selection-box').exists()).toBe(true)
    expect(selected(wrapper)).toEqual([false, true, true])
    window.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }))
    await nextTick()
    expect(wrapper.find('.selection-box').exists()).toBe(false)
    expect(ui.selectedTaskId).toBeNull()

    await wrapper.find('.downloads-toolbar__views button:last-child').trigger('click')
    pointerDown(wrapper.find('.selection-surface').element, 250, 145)
    window.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientX: 100, clientY: 55 }))
    await nextTick()
    expect(selected(wrapper)).toEqual([false, true, true])
    window.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }))
    wrapper.unmount()
  })

  it('selects an anchored range with Shift+left click without opening details', async () => {
    const { wrapper, ui } = setup()
    await wrapper.findAll('.download__select-control')[0]!.trigger('click')
    wrapper.findAll('[data-task-id]')[2]!.element.dispatchEvent(new MouseEvent('click', { bubbles: true, shiftKey: true }))
    await nextTick()
    expect(selected(wrapper)).toEqual([true, true, true])
    expect(ui.selectedTaskId).toBeNull()
    wrapper.findAll('.download__select-control')[1]!.element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, shiftKey: true }))
    await nextTick()
    expect(selected(wrapper)).toEqual([true, true, false])
    wrapper.unmount()
  })

  it('uses Explorer-like row clicks for anchor, range, and Ctrl toggle', async () => {
    const { wrapper, ui } = setup()
    const rows = wrapper.findAll('[data-task-id]')
    await rows[0]!.trigger('click')
    expect(selected(wrapper)).toEqual([true, false, false])
    expect(ui.selectedTaskId).toBeNull()

    await rows[2]!.trigger('click', { shiftKey: true })
    expect(selected(wrapper)).toEqual([true, true, true])
    await rows[1]!.trigger('click', { ctrlKey: true })
    expect(selected(wrapper)).toEqual([true, false, true])

    await rows[2]!.trigger('dblclick')
    expect(ui.selectedTaskId).toBe('c')
    wrapper.unmount()
  })

  it('keeps a failed deletion selected for retry', async () => {
    vi.spyOn(api, 'deleteDownload').mockRejectedValueOnce(new Error('server unavailable'))
    const { wrapper, downloads, ui } = setup()
    await wrapper.find('.download__select-control').trigger('click')
    await wrapper.find('.downloads-toolbar .btn--danger').trigger('click')
    ;(document.body.querySelector('.dialog__footer .btn--danger') as HTMLButtonElement).click()
    await vi.waitFor(() => expect(ui.toasts.some(toast => toast.detail === 'server unavailable')).toBe(true))
    expect(selected(wrapper)[0]).toBe(true)
    expect(downloads.tasks).toHaveLength(3)
    wrapper.unmount()
  })
})
