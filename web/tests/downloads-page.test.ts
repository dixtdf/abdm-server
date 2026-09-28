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

afterEach(() => {
  vi.restoreAllMocks()
  Reflect.deleteProperty(document, 'elementFromPoint')
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
    expect(wrapper.findAll('.download__select input').every(input => (input.element as HTMLInputElement).checked)).toBe(true)
    await wrapper.findAll('.download__select input')[2]!.setValue(false)
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
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: vi.fn().mockReturnValue(rows[1]!.element),
    })
    const down = new MouseEvent('pointerdown', { bubbles: true, button: 0, clientX: 10, clientY: 10 })
    Object.defineProperty(down, 'pointerType', { value: 'mouse' })
    rows[0]!.element.dispatchEvent(down)
    window.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientX: 10, clientY: 35 }))
    window.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }))
    rows[0]!.element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    expect(wrapper.findAll('.download__select input').map(input => (input.element as HTMLInputElement).checked)).toEqual([true, true, false])
    expect(ui.selectedTaskId).toBeNull()
    wrapper.unmount()
  })

  it('keeps a failed deletion selected for retry', async () => {
    vi.spyOn(api, 'deleteDownload').mockRejectedValueOnce(new Error('server unavailable'))
    const { wrapper, downloads, ui } = setup()
    await wrapper.find('.download__select input').setValue(true)
    await wrapper.find('.downloads-toolbar .btn--danger').trigger('click')
    ;(document.body.querySelector('.dialog__footer .btn--danger') as HTMLButtonElement).click()
    await vi.waitFor(() => expect(ui.toasts.some(toast => toast.detail === 'server unavailable')).toBe(true))
    expect((wrapper.find('.download__select input').element as HTMLInputElement).checked).toBe(true)
    expect(downloads.tasks).toHaveLength(3)
    wrapper.unmount()
  })
})
