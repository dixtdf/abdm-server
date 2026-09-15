<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import IconButton from './IconButton.vue'
import IconClock from './icons/IconClock.vue'
import IconConnections from './icons/IconConnections.vue'
import IconFile from './icons/IconFile.vue'
import IconGauge from './icons/IconGauge.vue'
import IconMore from './icons/IconMore.vue'
import IconPause from './icons/IconPause.vue'
import IconPlay from './icons/IconPlay.vue'
import IconTrash from './icons/IconTrash.vue'
import ProgressBar from './ProgressBar.vue'
import StatusPill from './StatusPill.vue'
import { useFormat } from '../composables/useFormat'
import { isPausable, isResumable, type Task } from '../api/types'

const props = defineProps<{ task: Task }>()

const emit = defineEmits<{
  pause: [id: string]
  resume: [id: string]
  remove: [id: string]
  details: [id: string]
}>()

const { t } = useI18n()
const fmt = useFormat()

const task = computed(() => props.task)
const unknownSize = computed(() => task.value.total < 0)
const animated = computed(() => task.value.state === 'DOWNLOADING')
const canPause = computed(() => isPausable(task.value.state))
const canResume = computed(() => isResumable(task.value.state))

const tone = computed<'primary' | 'success' | 'warning' | 'error'>(() => {
  switch (task.value.state) {
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

const progressLabel = computed(() =>
  t('download.progressLabel', {
    name: task.value.fileName,
    percent: fmt.percent(task.value.progress),
  }),
)

const totalLabel = computed(() =>
  unknownSize.value ? t('common.unknown') : fmt.bytes(task.value.total),
)

const metaLabel = computed(() => t('a11y.cardActions', { name: task.value.fileName }))

const sizeTitle = computed(() => `${t('download.downloaded')} / ${t('download.total')}`)
</script>

<template>
  <article
    class="card download"
    tabindex="0"
    :aria-label="task.fileName"
    @click="emit('details', task.id)"
    @keydown.enter.prevent="emit('details', task.id)"
  >
    <header class="download__head">
      <span class="download__icon" aria-hidden="true"><IconFile /></span>

      <div class="download__titles">
        <p class="download__name truncate" :title="task.fileName">{{ task.fileName }}</p>
        <p class="download__path truncate mono" :title="task.path">{{ task.path }}</p>
      </div>

      <StatusPill class="download__pill" :state="task.state" />

      <div class="download__actions" role="group" :aria-label="metaLabel">
        <IconButton
          v-if="canPause"
          :label="t('download.pause')"
          @click.stop="emit('pause', task.id)"
        >
          <IconPause />
        </IconButton>
        <IconButton
          v-else-if="canResume"
          :label="t('download.resume')"
          @click.stop="emit('resume', task.id)"
        >
          <IconPlay />
        </IconButton>
        <IconButton
          :label="t('download.remove')"
          variant="danger"
          @click.stop="emit('remove', task.id)"
        >
          <IconTrash />
        </IconButton>
        <IconButton
          :label="t('download.openDetails')"
          @click.stop="emit('details', task.id)"
        >
          <IconMore />
        </IconButton>
      </div>
    </header>

    <ProgressBar
      class="download__progress"
      :value="task.progress"
      :indeterminate="unknownSize"
      :animated="animated"
      :tone="tone"
      :label="progressLabel"
    />

    <footer class="download__meta">
      <span class="meta" :title="t('download.speed')">
        <IconGauge aria-hidden="true" />
        <span class="meta__value">{{ fmt.speed(task.speed) }}</span>
      </span>
      <span class="meta" :title="sizeTitle">
        <span class="meta__value">{{ fmt.bytes(task.downloaded) }} / {{ totalLabel }}</span>
      </span>
      <span class="meta" :title="t('download.eta')">
        <IconClock aria-hidden="true" />
        <span class="meta__value">{{ fmt.duration(task.etaSeconds) }}</span>
      </span>
      <span class="meta meta--chip" :title="t('download.connections')">
        <IconConnections aria-hidden="true" />
        <span class="meta__value">{{ t('download.connectionCount', { count: task.connections }) }}</span>
      </span>
    </footer>

    <p v-if="task.error" class="download__error">{{ task.error }}</p>
  </article>
</template>

<style scoped>
.download {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  cursor: pointer;
  transition: box-shadow var(--duration-base) var(--ease-standard),
    border-color var(--duration-base) var(--ease-standard),
    transform var(--duration-base) var(--ease-standard);
}

.download:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-card-hover);
}

.download__head {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.download__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border-radius: var(--radius-medium);
  background: var(--surface-secondary);
  color: var(--text-secondary);
}

.download__titles {
  min-width: 0;
  flex: 1;
}

.download__name {
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--text-primary);
}

.download__path {
  color: var(--text-muted);
  font-size: var(--text-xs);
}

/* The pill and the actions share the right hand slot: the actions are taken out
   of flow so a long file name always keeps the room, and they fade in over the
   pill on hover or keyboard focus. */
.download__actions {
  position: absolute;
  inset-inline-end: 0;
  top: 50%;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding-inline-start: var(--space-2);
  background: var(--surface);
  border-radius: var(--radius-small);
  opacity: 0;
  pointer-events: none;
  transform: translateY(-50%) translateX(4px);
  transition: opacity var(--duration-fast) var(--ease-standard),
    transform var(--duration-fast) var(--ease-standard);
}

.download:hover .download__actions,
.download:focus-visible .download__actions,
.download:focus-within .download__actions {
  opacity: 1;
  pointer-events: auto;
  transform: translateY(-50%);
}

.download:hover .download__pill,
.download:focus-within .download__pill {
  opacity: 0;
}

.download__pill {
  transition: opacity var(--duration-fast) var(--ease-standard);
}

.download__progress {
  margin-top: auto;
}

.download__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  min-height: 20px;
  font-size: var(--text-xs);
  color: var(--text-secondary);
}

.meta {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.meta svg {
  color: var(--text-muted);
}

.meta--chip {
  padding: 2px var(--space-2);
  border-radius: var(--radius-pill);
  background: var(--surface-secondary);
}

.meta__value {
  font-variant-numeric: tabular-nums;
}

.download__error {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-small);
  background: var(--error-soft);
  color: var(--error);
  font-size: var(--text-xs);
  word-break: break-word;
}

@media (hover: none) {
  .download__actions {
    position: static;
    padding-inline-start: 0;
    background: transparent;
    opacity: 1;
    pointer-events: auto;
    transform: none;
  }

  .download:hover .download__pill,
  .download:focus-within .download__pill {
    opacity: 1;
  }
}

@media (max-width: 820px) {
  .download__path {
    display: none;
  }

  .download__actions {
    position: static;
    padding-inline-start: 0;
    background: transparent;
    opacity: 1;
    pointer-events: auto;
    transform: none;
  }

  .download:hover .download__pill,
  .download:focus-within .download__pill {
    opacity: 1;
  }

  .download__head {
    flex-wrap: wrap;
  }
}
</style>
