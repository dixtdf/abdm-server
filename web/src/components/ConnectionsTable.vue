<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useFormat } from '../composables/useFormat'
import type { PartProgress } from '../api/types'

/**
 * The per connection table.
 *
 * It mirrors the part list of the AB Download Manager desktop client: one row per
 * connection with its state, downloaded bytes and the size of the range it owns.
 * Rows come from the `download.parts` event / the `parts` field of the REST
 * contract, so the table is live without polling.
 */
const props = withDefaults(
  defineProps<{
    parts: PartProgress[] | undefined
    /** Requested connection count; shown in the empty hint. */
    connections?: number
  }>(),
  { connections: 0 },
)

const { t } = useI18n()
const fmt = useFormat()

const rows = computed(() => props.parts ?? [])
const totalBytes = computed(() => rows.value.reduce((sum, part) => sum + Math.max(0, part.total), 0))
const downloadedBytes = computed(() =>
  rows.value.reduce((sum, part) => sum + Math.max(0, part.downloaded), 0),
)
const activeCount = computed(
  () => rows.value.filter((part) => part.state === 'DOWNLOADING' || part.state === 'CONNECTING').length,
)
</script>

<template>
  <section class="parts">
    <header class="parts__header">
      <h3 class="parts__title">{{ t('parts.title') }}</h3>
      <span v-if="rows.length" class="parts__summary">
        {{
          t('parts.summary', {
            count: rows.length,
            downloaded: fmt.bytes(downloadedBytes),
            total: fmt.bytes(totalBytes),
          })
        }}
        <span class="parts__active">· {{ t('download.connectionCount', { count: activeCount }) }}</span>
      </span>
    </header>

    <p v-if="!rows.length" class="parts__empty">
      {{ t('parts.empty') }}
      <span v-if="connections > 0" class="parts__empty-hint">
        ({{ t('download.connectionCount', { count: connections }) }})
      </span>
    </p>

    <div v-else class="parts__scroll">
      <table class="parts__table">
        <thead>
          <tr>
            <th scope="col" class="parts__col-index">#</th>
            <th scope="col">{{ t('parts.state') }}</th>
            <th scope="col" class="parts__num">{{ t('parts.downloaded') }}</th>
            <th scope="col" class="parts__num">{{ t('parts.total') }}</th>
            <th scope="col" class="parts__num">{{ t('parts.speed') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="part in rows" :key="part.index">
            <td class="parts__num mono">{{ part.index }}</td>
            <td>
              <span class="parts__state" :data-state="part.state">
                <span class="parts__dot" aria-hidden="true" />
                {{ t(`part.state.${part.state}`) }}
              </span>
            </td>
            <td class="parts__num mono">{{ fmt.bytes(part.downloaded) }}</td>
            <td class="parts__num mono">
              {{ part.total > 0 ? fmt.bytes(part.total) : t('common.unknown') }}
            </td>
            <td class="parts__num mono">
              {{ part.state === 'DOWNLOADING' ? fmt.speed(part.speed) : '—' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-if="rows.length" class="parts__hint">{{ t('parts.repartitionHint') }}</p>
  </section>
</template>

<style scoped>
.parts {
  margin-top: var(--space-5);
  padding-top: var(--space-4);
  border-top: 1px solid var(--border);
}

.parts__header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.parts__title {
  font-size: var(--text-sm);
  font-weight: 650;
  color: var(--text-primary);
}

.parts__summary {
  font-size: var(--text-xs);
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.parts__active {
  color: var(--primary);
}

.parts__empty {
  margin-top: var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.parts__empty-hint {
  color: var(--text-secondary);
}

.parts__scroll {
  margin-top: var(--space-3);
  max-height: 320px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-medium);
  background: var(--surface-secondary);
}

.parts__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-xs);
}

.parts__table thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--surface-secondary);
  color: var(--text-muted);
  font-weight: 600;
  text-align: start;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.parts__table td {
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  color: var(--text-primary);
  white-space: nowrap;
}

.parts__table tbody tr:last-child td {
  border-bottom: 0;
}

.parts__table tbody tr:hover td {
  background: var(--surface-elevated);
}

.parts__num {
  text-align: end;
  font-variant-numeric: tabular-nums;
}

.parts__col-index {
  width: 2.5rem;
}

.parts__state {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

.parts__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-muted);
  flex: none;
}

.parts__state[data-state='DOWNLOADING'] .parts__dot {
  background: var(--primary);
  animation: parts-pulse 1.2s var(--ease-standard) infinite;
}

.parts__state[data-state='CONNECTING'] .parts__dot {
  background: var(--warning);
}

.parts__state[data-state='DONE'] .parts__dot {
  background: var(--success);
}

.parts__state[data-state='FAILED'] .parts__dot {
  background: var(--error);
}

.parts__hint {
  margin-top: var(--space-3);
  font-size: var(--text-xs);
  color: var(--text-muted);
}

@keyframes parts-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}
</style>
