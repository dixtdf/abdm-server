<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppButton from '../components/AppButton.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import EmptyState from '../components/EmptyState.vue'
import IconHistory from '../components/icons/IconHistory.vue'
import IconRefresh from '../components/icons/IconRefresh.vue'
import IconTrash from '../components/icons/IconTrash.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusPill from '../components/StatusPill.vue'
import { useFormat } from '../composables/useFormat'
import { useDownloadsStore } from '../stores/downloads'
import { useUiStore } from '../stores/ui'
import { DOWNLOAD_STATES, type DownloadState } from '../api/types'

const { t } = useI18n()
const fmt = useFormat()
const downloads = useDownloadsStore()
const ui = useUiStore()

onMounted(() => {
  if (!downloads.historyLoaded) void downloads.loadHistory(100)
})

const confirming = ref(false)

const subtitle = computed(() => t('history.count', { count: downloads.history.length }))
const isEmpty = computed(() => downloads.historyLoaded && downloads.history.length === 0)

function isState(value: string): value is DownloadState {
  return (DOWNLOAD_STATES as readonly string[]).includes(value)
}

function sizeLabel(size: number): string {
  return size < 0 ? t('common.unknown') : fmt.bytes(size)
}

function clearHistory(): void {
  void (async () => {
    try {
      await downloads.clearHistory()
      ui.pushToast({ kind: 'success', key: 'toast.historyCleared' })
    } catch (caught) {
      ui.pushError(caught)
    } finally {
      confirming.value = false
    }
  })()
}
</script>

<template>
  <section>
    <PageHeader :title="t('history.title')" :subtitle="subtitle">
      <template #actions>
        <AppButton
          variant="secondary"
          :disabled="downloads.historyLoading"
          @click="downloads.loadHistory(100)"
        >
          <IconRefresh />
          <span>{{ t('common.refresh') }}</span>
        </AppButton>
        <AppButton
          variant="danger"
          :disabled="downloads.history.length === 0"
          @click="confirming = true"
        >
          <IconTrash />
          <span>{{ t('history.clear') }}</span>
        </AppButton>
      </template>
    </PageHeader>

    <div v-if="downloads.historyError" class="page-error">
      <EmptyState
        :title="t('empty.loadFailedTitle')"
        :description="t('empty.loadFailedDescription')"
      >
        <template #icon><IconHistory /></template>
        <template #actions>
          <AppButton variant="secondary" @click="downloads.loadHistory(100)">
            {{ t('common.retry') }}
          </AppButton>
        </template>
      </EmptyState>
    </div>

    <div v-else-if="isEmpty" class="page-empty">
      <EmptyState :title="t('empty.historyTitle')" :description="t('empty.historyDescription')">
        <template #icon><IconHistory /></template>
      </EmptyState>
    </div>

    <div v-else class="card table">
      <table class="table__grid">
        <thead>
          <tr>
            <th scope="col">{{ t('history.fileName') }}</th>
            <th scope="col" class="table__url">{{ t('history.url') }}</th>
            <th scope="col" class="table__num">{{ t('history.size') }}</th>
            <th scope="col">{{ t('history.outcome') }}</th>
            <th scope="col" class="table__num">{{ t('history.finishedAt') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="entry in downloads.history" :key="entry.id">
            <td class="table__name truncate" :title="entry.fileName">{{ entry.fileName }}</td>
            <td class="table__url truncate mono" :title="entry.url">{{ entry.url }}</td>
            <td class="table__num">{{ sizeLabel(entry.size) }}</td>
            <td>
              <StatusPill v-if="isState(entry.outcome)" :state="entry.outcome" />
              <span v-else class="table__outcome">{{ entry.outcome }}</span>
            </td>
            <td class="table__num">{{ fmt.dateTime(entry.finishedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <ConfirmDialog
      :open="confirming"
      :title="t('history.clearTitle')"
      :message="t('history.clearMessage', { count: downloads.history.length })"
      :confirm-label="t('history.clear')"
      danger
      @confirm="clearHistory"
      @cancel="confirming = false"
    />
  </section>
</template>

<style scoped>
.table {
  overflow: hidden;
}

.table__grid {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-sm);
}

.table__grid th {
  padding: var(--space-3) var(--space-4);
  text-align: start;
  font-size: var(--text-xs);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  color: var(--text-muted);
  background: var(--surface-secondary);
  white-space: nowrap;
}

.table__grid td {
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--border);
  color: var(--text-primary);
  vertical-align: middle;
}

.table__grid tbody tr:hover td {
  background: var(--surface-secondary);
}

.table__name {
  max-width: 260px;
  font-weight: 550;
}

.table__url {
  max-width: 380px;
  color: var(--text-secondary);
}

.table__num {
  text-align: end;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  color: var(--text-secondary);
}

.table__outcome {
  color: var(--text-secondary);
}

.page-error,
.page-empty {
  margin-top: var(--space-2);
}

@media (max-width: 820px) {
  .table__grid th,
  .table__grid td {
    padding: var(--space-2);
  }

  .table__url {
    display: none;
  }
}
</style>
