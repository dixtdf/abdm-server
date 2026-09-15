<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    /** Ratio between 0 and 1. Ignored when `indeterminate` is set. */
    value?: number
    /** Used when the server reports an unknown total size. */
    indeterminate?: boolean
    /** Animated sheen while bytes are flowing. */
    animated?: boolean
    /** Accessible name for the progressbar. */
    label?: string
    /** Draws the bar in the error/warning palette. */
    tone?: 'primary' | 'success' | 'warning' | 'error'
  }>(),
  {
    value: 0,
    indeterminate: false,
    animated: false,
    label: undefined,
    tone: 'primary',
  },
)

const clamped = computed(() => Math.min(1, Math.max(0, Number.isFinite(props.value) ? props.value : 0)))
const percent = computed(() => Math.round(clamped.value * 100))
</script>

<template>
  <div
    class="track"
    :class="[`track--${tone}`, { 'track--indeterminate': indeterminate }]"
    role="progressbar"
    :aria-label="label"
    :aria-valuemin="0"
    :aria-valuemax="100"
    :aria-valuenow="indeterminate ? undefined : percent"
    :aria-valuetext="indeterminate ? undefined : `${percent}%`"
    :data-indeterminate="indeterminate ? 'true' : 'false'"
  >
    <div v-if="indeterminate" class="track__indeterminate" />
    <div v-else class="track__fill" :style="{ width: `${percent}%` }">
      <span v-if="animated" class="track__sheen" aria-hidden="true" />
    </div>
  </div>
</template>

<style scoped>
.track {
  position: relative;
  height: var(--progress-height);
  width: 100%;
  border-radius: var(--radius-pill);
  background: var(--surface-secondary);
  overflow: hidden;
  --bar-color: var(--primary);
  --bar-color-soft: var(--primary-hover);
}

.track--success {
  --bar-color: var(--success);
  --bar-color-soft: var(--success);
}

.track--warning {
  --bar-color: var(--warning);
  --bar-color-soft: var(--warning);
}

.track--error {
  --bar-color: var(--error);
  --bar-color-soft: var(--error);
}

.track__fill {
  position: relative;
  height: 100%;
  border-radius: var(--radius-pill);
  background: linear-gradient(90deg, var(--bar-color), var(--bar-color-soft));
  transition: width var(--duration-slow) var(--ease-standard);
  overflow: hidden;
}

.track__sheen {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    100deg,
    transparent 20%,
    color-mix(in srgb, var(--surface) 55%, transparent) 50%,
    transparent 80%
  );
  animation: track-sheen 1.6s linear infinite;
}

.track--indeterminate .track__indeterminate {
  position: absolute;
  inset-block: 0;
  width: 40%;
  border-radius: var(--radius-pill);
  background: linear-gradient(90deg, var(--bar-color), var(--bar-color-soft));
  animation: track-slide 1.5s var(--ease-standard) infinite;
}

@keyframes track-sheen {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(100%);
  }
}

@keyframes track-slide {
  0% {
    left: -40%;
  }
  100% {
    left: 100%;
  }
}
</style>
