<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import AppButton from './AppButton.vue'
import ConnectionStepper from './ConnectionStepper.vue'
import IconButton from './IconButton.vue'
import IconClose from './icons/IconClose.vue'
import IconCopy from './icons/IconCopy.vue'
import IconPause from './icons/IconPause.vue'
import IconPlay from './icons/IconPlay.vue'
import IconTrash from './icons/IconTrash.vue'
import ProgressBar from './ProgressBar.vue'
import StatusPill from './StatusPill.vue'
import { useBodyScrollLock } from '../composables/useBodyScrollLock'
import { useFormat } from '../composables/useFormat'
import { useUiStore } from '../stores/ui'
import { isPausable, isResumable, type DownloadState, type Task } from '../api/types'

const props = defineProps<{
  /** `null` closes the drawer, which drives the slide-out transition. */
  task: Task | null
  maxConnections: number
}>()

const emit = defineEmits<{
  close: []
  pause: [id: string]
  resume: [id: string]
  remove: [id: string]
  connections: [id: string, value: number]
}>()

const { t } = useI18n()
const fmt = useFormat()
const ui = useUiStore()

const open = computed(() => props.task !== null)
useBodyScrollLock(open)

const taskId = computed(() => props.task?.id ?? '')
const state = computed<DownloadState>(() => props.task?.state ?? 'QUEUED')
const canPause = computed(() => (props.task ? isPausable(props.task.state) : false))
const canResume = computed(() => (props.task ? isResumable(props.task.state) : false))
const unknownSize = computed(() => (props.task ? props.task.total < 0 : false))

const tone = computed<'primary' | 'success' | 'warning' | 'error'>(() => {
  switch (state.value) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'error'
    case 'PAUSING':
    case 'RECOVERING':
      return 'warning'
    default:
      return 'primary'
  }
})

const progress = computed(() => props.task?.progress ?? 0)
const downloaded = computed(() => props.task?.downloaded ?? 0)
const connections = computed(() => props.task?.connections ?? 0)
const activeConnections = computed(() => props.task?.activeConnections ?? 0)
const speed = computed(() => props.task?.speed ?? 0)
const averageSpeed = computed(() => props.task?.averageSpeed ?? 0)
const supportsRange = computed(() => props.task?.supportsRange ?? false)
const hls = computed(() => props.task?.hls ?? false)
const checksum = computed(() => props.task?.checksum ?? null)
const queuePosition = computed(() => props.task?.queuePosition ?? null)
const errorMessage = computed(() => props.task?.error ?? null)

const progressLabel = computed(() =>
  t('download.progressLabel', {
    name: props.task?.fileName ?? '',
    percent: fmt.percent(progress.value),
  }),
)

const etaLabel = computed(() => fmt.duration(props.task?.etaSeconds ?? -1))
const sizeLabel = computed(() =>
  unknownSize.value ? t('common.unknown') : fmt.bytes(props.task?.total ?? 0),
)
const limitLabel = computed(() =>
  (props.task?.speedLimit ?? 0) > 0 ? fmt.speed(props.task?.speedLimit ?? 0) : t('common.unlimited'),
)
const yesNo = (value: boolean): string => (value ? t('common.enabled') : t('common.disabled'))

async function copy(value: string | undefined, key: string): Promise<void> {
  if (!value) return
  try {
    await navigator.clipboard?.writeText(value)
    ui.pushToast({ kind: 'success', key })
  } catch {
    ui.pushToast({ kind: 'error', key: 'toast.operationFailed' })
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="drawer">
      <div v-if="task" class="drawer" role="presentation">
        <div class="drawer__scrim" @click="emit('close')" />

        <aside
          class="drawer__panel"
          role="dialog"
          aria-modal="true"
          :aria-label="t('details.title')"
        >
          <header class="drawer__header">
            <div class="drawer__heading">
              <p class="drawer__name truncate" :title="task.fileName">{{ task.fileName }}</p>
              <StatusPill :state="state" />
            </div>
            <IconButton :label="t('a11y.closeDetails')" @click="emit('close')">
              <IconClose />
            </IconButton>
          </header>

          <div class="drawer__body">
            <ProgressBar
              :value="progress"
              :indeterminate="unknownSize"
              :animated="state === 'DOWNLOADING'"
              :tone="tone"
              :label="progressLabel"
            />

            <div class="drawer__percent">
              <span>{{ fmt.percent(progress) }}</span>
              <span>{{ fmt.bytes(downloaded) }} / {{ sizeLabel }}</span>
            </div>

            <dl class="rows">
              <div class="row">
                <dt class="row__label">{{ t('details.fileName') }}</dt>
                <dd class="row__value row__value--break">{{ task.fileName }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.url') }}</dt>
                <dd class="row__value row__value--break mono">{{ task.url }}</dd>
                <dd class="row__action">
                  <IconButton
                    :label="t('details.copyUrl')"
                    size="sm"
                    @click="copy(task.url, 'toast.copiedUrl')"
                  >
                    <IconCopy />
                  </IconButton>
                </dd>
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.folder') }}</dt>
                <dd class="row__value row__value--break mono">{{ task.folder }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.path') }}</dt>
                <dd class="row__value row__value--break mono">{{ task.path }}</dd>
                <dd class="row__action">
                  <IconButton
                    :label="t('details.copyPath')"
                    size="sm"
                    @click="copy(task.path, 'toast.copiedPath')"
                  >
                    <IconCopy />
                  </IconButton>
                </dd>
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.status') }}</dt>
                <dd class="row__value">{{ t(`download.status.${state}`) }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.downloaded') }}</dt>
                <dd class="row__value">{{ fmt.bytes(downloaded) }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.total') }}</dt>
                <dd class="row__value">{{ sizeLabel }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.speed') }}</dt>
                <dd class="row__value">{{ fmt.speed(speed) }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.averageSpeed') }}</dt>
                <dd class="row__value">{{ fmt.speed(averageSpeed) }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row row--stack">
                <dt class="row__label">{{ t('details.connections') }}</dt>
                <dd class="row__value">
                  <ConnectionStepper
                    :model-value="connections"
                    :max="maxConnections"
                    :label="t('a11y.connectionsValue', { name: task.fileName })"
                    @change="emit('connections', taskId, $event)"
                  />
                </dd>
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.activeConnections') }}</dt>
                <dd class="row__value">{{ activeConnections }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.eta') }}</dt>
                <dd class="row__value">{{ etaLabel }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.supportsRange') }}</dt>
                <dd class="row__value">{{ yesNo(supportsRange) }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.hls') }}</dt>
                <dd class="row__value">{{ yesNo(hls) }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.speedLimit') }}</dt>
                <dd class="row__value">{{ limitLabel }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.checksum') }}</dt>
                <dd class="row__value row__value--break mono">{{ checksum ?? t('common.none') }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.created') }}</dt>
                <dd class="row__value">{{ fmt.dateTime(task.createdAt) }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.started') }}</dt>
                <dd class="row__value">{{ fmt.dateTime(task.startedAt) }}</dd>
                <dd class="row__action" />
              </div>

              <div class="row">
                <dt class="row__label">{{ t('details.completed') }}</dt>
                <dd class="row__value">{{ fmt.dateTime(task.completedAt) }}</dd>
                <dd class="row__action" />
              </div>

              <div v-if="queuePosition !== null" class="row">
                <dt class="row__label">{{ t('details.queuePosition') }}</dt>
                <dd class="row__value">{{ queuePosition }}</dd>
                <dd class="row__action" />
              </div>

              <div v-if="errorMessage" class="row row--stack">
                <dt class="row__label">{{ t('details.error') }}</dt>
                <dd class="row__value row__value--error">{{ errorMessage }}</dd>
              </div>
            </dl>
          </div>

          <footer class="drawer__footer">
            <AppButton v-if="canPause" variant="secondary" @click="emit('pause', taskId)">
              <IconPause />
              <span>{{ t('download.pause') }}</span>
            </AppButton>
            <AppButton v-else-if="canResume" variant="secondary" @click="emit('resume', taskId)">
              <IconPlay />
              <span>{{ t('download.resume') }}</span>
            </AppButton>

            <AppButton variant="danger" @click="emit('remove', taskId)">
              <IconTrash />
              <span>{{ t('download.remove') }}</span>
            </AppButton>
          </footer>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.drawer {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: flex;
  justify-content: flex-end;
}

.drawer__scrim {
  position: absolute;
  inset: 0;
  background: var(--overlay);
}

.drawer__panel {
  position: relative;
  display: flex;
  flex-direction: column;
  width: var(--drawer-width);
  max-width: 100%;
  height: 100%;
  background: var(--surface-elevated);
  border-inline-start: 1px solid var(--border);
  box-shadow: var(--shadow-dialog);
}

.drawer__header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-5);
  border-bottom: 1px solid var(--border);
}

.drawer__heading {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-width: 0;
  flex: 1;
  align-items: flex-start;
}

.drawer__name {
  font-size: var(--text-lg);
  font-weight: 650;
  max-width: 100%;
}

.drawer__body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-5);
}

.drawer__percent {
  display: flex;
  justify-content: space-between;
  margin-top: var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.rows {
  margin-top: var(--space-5);
  display: flex;
  flex-direction: column;
}

.row {
  display: grid;
  grid-template-columns: 128px 1fr 32px;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border);
}

.row:last-child {
  border-bottom: 0;
}

.row--stack {
  grid-template-columns: 128px 1fr;
}

.row__label {
  font-size: var(--text-xs);
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.row__value {
  font-size: var(--text-sm);
  color: var(--text-primary);
  word-break: break-word;
}

.row__value--break {
  word-break: break-all;
  font-size: var(--text-xs);
}

.row__value--error {
  color: var(--error);
}

.row__action {
  display: flex;
  justify-content: flex-end;
}

.drawer__footer {
  display: flex;
  gap: var(--space-2);
  padding: var(--space-4) var(--space-5);
  border-top: 1px solid var(--border);
}

.drawer__footer :deep(.btn) {
  flex: 1;
}

.drawer-enter-active .drawer__panel,
.drawer-leave-active .drawer__panel {
  transition: transform var(--duration-slow) var(--ease-standard);
}

.drawer-enter-active,
.drawer-leave-active {
  transition: opacity var(--duration-base) var(--ease-standard);
}

.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}

.drawer-enter-from .drawer__panel,
.drawer-leave-to .drawer__panel {
  transform: translateX(100%);
}

@media (max-width: 820px) {
  .drawer__panel {
    width: 100%;
    border-inline-start: 0;
  }

  .row {
    grid-template-columns: 104px 1fr 32px;
  }

  .row--stack {
    grid-template-columns: 104px 1fr;
  }
}
</style>
