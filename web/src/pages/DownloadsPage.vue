<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AddDownloadDialog from '../components/AddDownloadDialog.vue'
import AppButton from '../components/AppButton.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import DownloadCard from '../components/DownloadCard.vue'
import DownloadDetailsDrawer from '../components/DownloadDetailsDrawer.vue'
import EmptyState from '../components/EmptyState.vue'
import IconDownload from '../components/icons/IconDownload.vue'
import IconPlus from '../components/icons/IconPlus.vue'
import IconRefresh from '../components/icons/IconRefresh.vue'
import IconTrash from '../components/icons/IconTrash.vue'
import PageHeader from '../components/PageHeader.vue'
import { useDownloadsStore } from '../stores/downloads'
import { useSettingsStore } from '../stores/settings'
import { useUiStore } from '../stores/ui'
import type { Task } from '../api/types'
import { readStorage, STORAGE_KEYS, writeStorage } from '../utils/storage'

const { t } = useI18n()
const downloads = useDownloadsStore()
const settings = useSettingsStore()
const ui = useUiStore()

onMounted(() => {
  void settings.load()
  if (!downloads.loaded) void downloads.load()
})

const pendingRemove = ref<Task[]>([])
const deleteFile = ref(false)
const removing = ref(false)
const view = ref<'list' | 'card'>(readStorage(STORAGE_KEYS.downloadsView) === 'card' ? 'card' : 'list')
const selectedIds = ref<Set<string>>(new Set())
const selectedCount = computed(() => selectedIds.value.size)
const allSelected = computed(() => downloads.tasks.length > 0 && downloads.tasks.every(task => selectedIds.value.has(task.id)))
const someSelected = computed(() => selectedCount.value > 0 && !allSelected.value)
const selectAllInput = ref<HTMLInputElement | null>(null)

watch([someSelected, selectAllInput], () => {
  if (selectAllInput.value) selectAllInput.value.indeterminate = someSelected.value
}, { flush: 'post' })

watch(() => downloads.tasks.map(task => task.id), (ids) => {
  const current = new Set(ids)
  if ([...selectedIds.value].some(id => !current.has(id))) {
    selectedIds.value = new Set([...selectedIds.value].filter(id => current.has(id)))
  }
})

function setView(value: 'list' | 'card'): void {
  view.value = value
  writeStorage(STORAGE_KEYS.downloadsView, value)
}

function selectTask(id: string, selected: boolean): void {
  const next = new Set(selectedIds.value)
  if (selected) next.add(id)
  else next.delete(id)
  selectedIds.value = next
}

function toggleAll(): void {
  selectedIds.value = allSelected.value ? new Set() : new Set(downloads.tasks.map(task => task.id))
}

// A mouse drag across rows/cards selects the contiguous range it touches.
let dragStart: { id: string; x: number; y: number; original: Set<string> } | null = null
let dragged = false
let suppressClick = false

function onPointerDown(event: PointerEvent): void {
  if (event.pointerType !== 'mouse' || event.button !== 0 || removing.value) return
  const target = event.target as HTMLElement
  if (target.closest('button, input, label, a')) return
  const item = target.closest<HTMLElement>('[data-task-id]')
  if (!item?.dataset.taskId) return
  event.preventDefault()
  dragStart = { id: item.dataset.taskId, x: event.clientX, y: event.clientY, original: new Set(selectedIds.value) }
  dragged = false
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp, { once: true })
}

function onPointerMove(event: PointerEvent): void {
  if (!dragStart) return
  if (!dragged && Math.hypot(event.clientX - dragStart.x, event.clientY - dragStart.y) < 5) return
  dragged = true
  const hovered = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>('[data-task-id]')
  const ids = downloads.tasks.map(task => task.id)
  const start = ids.indexOf(dragStart.id)
  const end = ids.indexOf(hovered?.dataset.taskId ?? dragStart.id)
  if (start < 0 || end < 0) return
  const next = event.ctrlKey || event.metaKey ? new Set(dragStart.original) : new Set<string>()
  for (let index = Math.min(start, end); index <= Math.max(start, end); index++) next.add(ids[index]!)
  selectedIds.value = next
  event.preventDefault()
}

function onPointerUp(): void {
  window.removeEventListener('pointermove', onPointerMove)
  if (dragged) {
    suppressClick = true
    window.setTimeout(() => { suppressClick = false }, 0)
  }
  dragStart = null
  dragged = false
}

function onClickCapture(event: MouseEvent): void {
  if (!suppressClick) return
  event.preventDefault()
  event.stopPropagation()
  suppressClick = false
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
})

const selectedTask = computed(() =>
  ui.selectedTaskId ? downloads.taskById(ui.selectedTaskId) : null,
)

const subtitle = computed(() => t('download.count', { count: downloads.tasks.length }))

async function guarded(action: () => Promise<unknown>, fallbackKey?: string): Promise<void> {
  try {
    await action()
  } catch (caught) {
    ui.pushError(caught, fallbackKey)
  }
}

function onPause(id: string): void {
  void guarded(() => downloads.pause(id))
}

function onResume(id: string): void {
  void guarded(() => downloads.resume(id))
}

function askRemove(id: string): void {
  const task = downloads.taskById(id)
  pendingRemove.value = task ? [task] : []
  deleteFile.value = false
}

function askRemoveSelected(): void {
  pendingRemove.value = downloads.tasks.filter(task => selectedIds.value.has(task.id))
  deleteFile.value = false
}

async function confirmRemove(): Promise<void> {
  const tasks = pendingRemove.value
  if (!tasks.length || removing.value) return
  const alsoDeleteFile = deleteFile.value
  pendingRemove.value = []
  removing.value = true
  let removed = 0
  for (const task of tasks) {
    try {
      await downloads.remove(task.id, alsoDeleteFile)
      selectTask(task.id, false)
      if (ui.selectedTaskId === task.id) ui.selectTask(null)
      removed++
    } catch (caught) {
      ui.pushError(caught)
    }
  }
  removing.value = false
  if (removed === 1 && tasks.length === 1) {
    ui.pushToast({ kind: 'success', key: 'toast.downloadRemoved', params: { name: tasks[0]!.fileName } })
  } else if (removed > 0) {
    ui.pushToast({ kind: 'success', key: 'download.removedCount', params: { count: removed } })
  }
}

function onConnections(id: string, value: number): void {
  void guarded(() => downloads.setConnections(id, value), 'details.connectionChangeFailed')
}

function onCreated(): void {
  ui.selectTask(null)
}
</script>

<template>
  <section>
    <PageHeader :title="t('download.title')" :subtitle="subtitle">
      <template #actions>
        <AppButton variant="secondary" :disabled="downloads.loading" @click="downloads.load()">
          <IconRefresh />
          <span>{{ t('common.refresh') }}</span>
        </AppButton>
        <AppButton @click="ui.openAddDownload()">
          <IconPlus />
          <span>{{ t('download.add') }}</span>
        </AppButton>
      </template>
    </PageHeader>

    <div class="downloads-toolbar" role="toolbar" :aria-label="t('download.selectionToolbar')">
      <label class="checkbox downloads-toolbar__all">
        <input ref="selectAllInput" type="checkbox" :checked="allSelected" :disabled="downloads.isEmpty || removing" @change="toggleAll" />
        <span>{{ t('download.selectAll') }}</span>
      </label>
      <span v-if="selectedCount" class="downloads-toolbar__count">{{ t('download.selectedCount', { count: selectedCount }) }}</span>
      <AppButton v-if="selectedCount" variant="danger" size="sm" :disabled="removing" @click="askRemoveSelected">
        <IconTrash />
        <span>{{ t('download.deleteSelected') }}</span>
      </AppButton>
      <div class="downloads-toolbar__views" role="group" :aria-label="t('download.viewMode')">
        <button type="button" :class="{ active: view === 'list' }" :aria-pressed="view === 'list'" @click="setView('list')">{{ t('download.listView') }}</button>
        <button type="button" :class="{ active: view === 'card' }" :aria-pressed="view === 'card'" @click="setView('card')">{{ t('download.cardView') }}</button>
      </div>
    </div>

    <div v-if="downloads.error" class="page-error">
      <EmptyState
        :title="t('empty.loadFailedTitle')"
        :description="t('empty.loadFailedDescription')"
      >
        <template #icon><IconDownload /></template>
        <template #actions>
          <AppButton variant="secondary" @click="downloads.load()">{{ t('common.retry') }}</AppButton>
        </template>
      </EmptyState>
    </div>

    <div v-else-if="downloads.isEmpty" class="page-empty">
      <EmptyState
        :title="t('empty.downloadsTitle')"
        :description="t('empty.downloadsDescription')"
      >
        <template #icon><IconDownload /></template>
        <template #actions>
          <AppButton @click="ui.openAddDownload()">
            <IconPlus />
            <span>{{ t('download.add') }}</span>
          </AppButton>
        </template>
      </EmptyState>
    </div>

    <div v-else class="list" :class="`list--${view}`" @pointerdown="onPointerDown" @click.capture="onClickCapture">
      <DownloadCard
        v-for="task in downloads.tasks"
        :key="task.id"
        :task="task"
        :view="view"
        :selected="selectedIds.has(task.id)"
        @pause="onPause"
        @resume="onResume"
        @remove="askRemove"
        @details="ui.selectTask"
        @select="selectTask"
      />
    </div>

    <AddDownloadDialog
      :open="ui.addDownloadOpen"
      @close="ui.closeAddDownload()"
      @created="onCreated"
    />

    <DownloadDetailsDrawer
      :task="selectedTask"
      :max-connections="settings.maxConnections"
      @close="ui.selectTask(null)"
      @pause="onPause"
      @resume="onResume"
      @remove="askRemove"
      @connections="onConnections"
    />

    <ConfirmDialog
      :open="pendingRemove.length > 0"
      :title="t(pendingRemove.length > 1 ? 'download.deleteSelectedTitle' : 'download.deleteTitle')"
      :message="pendingRemove.length > 1 ? t('download.deleteSelectedMessage', { count: pendingRemove.length }) : t('download.deleteMessage', { name: pendingRemove[0]?.fileName ?? '' })"
      :confirm-label="t('download.remove')"
      :extra-option-label="t('download.deleteFile')"
      :extra-option-checked="deleteFile"
      danger
      @update:extra-option-checked="deleteFile = $event"
      @confirm="confirmRemove"
      @cancel="pendingRemove = []"
    />
  </section>
</template>

<style scoped>
.list {
  display: grid;
  gap: var(--space-3);
}

.list--card {
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
}

.list--list {
  grid-template-columns: minmax(0, 1fr);
  gap: var(--space-2);
}

.downloads-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-height: 38px;
  margin-bottom: var(--space-4);
}

.downloads-toolbar__all { font-size: var(--text-sm); }
.downloads-toolbar__count { color: var(--text-secondary); font-size: var(--text-sm); }
.downloads-toolbar__views {
  display: inline-flex;
  margin-inline-start: auto;
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: var(--radius-small);
  background: var(--surface-secondary);
}
.downloads-toolbar__views button {
  padding: 4px var(--space-3);
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}
.downloads-toolbar__views button.active {
  background: var(--surface);
  color: var(--primary);
  box-shadow: var(--shadow-card);
}

.page-error,
.page-empty {
  margin-top: var(--space-2);
}

@media (min-width: 2000px) {
  .list--card {
    grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  }
}

@media (max-width: 820px) {
  .list--card {
    grid-template-columns: minmax(0, 1fr);
  }

  .downloads-toolbar { flex-wrap: wrap; }
}
</style>
