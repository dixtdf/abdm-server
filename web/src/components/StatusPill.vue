<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DownloadState } from '../api/types'

const props = defineProps<{ state: DownloadState }>()
const { t } = useI18n()

const TONE: Record<DownloadState, string> = {
  QUEUED: 'neutral',
  CONNECTING: 'info',
  DOWNLOADING: 'primary',
  PAUSING: 'warning',
  PAUSED: 'neutral',
  COMPLETING: 'primary',
  COMPLETED: 'success',
  FAILED: 'error',
  CANCELED: 'neutral',
  RECOVERING: 'warning',
}

const tone = computed(() => TONE[props.state] ?? 'neutral')
const label = computed(() => t(`download.status.${props.state}`))
</script>

<template>
  <span class="pill" :class="`pill--${tone}`">
    <span class="pill__dot" aria-hidden="true" />
    <span class="pill__text">{{ label }}</span>
  </span>
</template>

<style scoped>
.pill {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: 3px 10px 3px 8px;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.01em;
  white-space: nowrap;
}

.pill__dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-pill);
  background: currentColor;
}

.pill--neutral {
  background: var(--muted-soft);
  color: var(--text-secondary);
}

.pill--info {
  background: var(--primary-soft);
  color: var(--primary);
}

.pill--primary {
  background: var(--primary-soft);
  color: var(--primary);
}

.pill--success {
  background: var(--success-soft);
  color: var(--success);
}

.pill--warning {
  background: var(--warning-soft);
  color: var(--warning);
}

.pill--error {
  background: var(--error-soft);
  color: var(--error);
}

.pill--primary .pill__dot {
  animation: pill-pulse 1.4s var(--ease-standard) infinite;
}

@keyframes pill-pulse {
  50% {
    opacity: 0.35;
  }
}
</style>
