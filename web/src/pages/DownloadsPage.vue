<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
import PageHeader from '../components/PageHeader.vue'
import { useDownloadsStore } from '../stores/downloads'
import { useSettingsStore } from '../stores/settings'
import { useUiStore } from '../stores/ui'
import type { Task } from '../api/types'

const { t } = useI18n()
const downloads = useDownloadsStore()
const settings = useSettingsStore()
const ui = useUiStore()

onMounted(() => {
  void settings.load()
  if (!downloads.loaded) void downloads.load()
})

const pendingRemove = ref<Task | null>(null)
const deleteFile = ref(false)

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
  pendingRemove.value = downloads.taskById(id)
  deleteFile.value = false
}

function confirmRemove(): void {
  const task = pendingRemove.value
  if (!task) return
  pendingRemove.value = null
  void guarded(async () => {
    await downloads.remove(task.id, deleteFile.value)
    if (ui.selectedTaskId === task.id) ui.selectTask(null)
    ui.pushToast({
      kind: 'success',
      key: 'toast.downloadRemoved',
      params: { name: task.fileName },
    })
  })
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

    <div v-else class="list">
      <DownloadCard
        v-for="task in downloads.tasks"
        :key="task.id"
        :task="task"
        @pause="onPause"
        @resume="onResume"
        @remove="askRemove"
        @details="ui.selectTask"
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
      :open="pendingRemove !== null"
      :title="t('download.deleteTitle')"
      :message="t('download.deleteMessage', { name: pendingRemove?.fileName ?? '' })"
      :confirm-label="t('download.remove')"
      :extra-option-label="t('download.deleteFile')"
      :extra-option-checked="deleteFile"
      danger
      @update:extra-option-checked="deleteFile = $event"
      @confirm="confirmRemove"
      @cancel="pendingRemove = null"
    />
  </section>
</template>

<style scoped>
.list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-3);
}

.page-error,
.page-empty {
  margin-top: var(--space-2);
}

@media (min-width: 2000px) {
  .list {
    grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  }
}

@media (max-width: 820px) {
  .list {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
