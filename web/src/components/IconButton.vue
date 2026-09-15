<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    /** Accessible name, also used as the native tooltip. */
    label: string
    variant?: 'ghost' | 'soft' | 'danger'
    size?: 'sm' | 'md'
    disabled?: boolean
    active?: boolean
    /** Optional override when the tooltip should differ from the accessible name. */
    tooltip?: string
  }>(),
  {
    variant: 'ghost',
    size: 'md',
    disabled: false,
    active: false,
    tooltip: undefined,
  },
)

const title = computed(() => props.tooltip ?? props.label)
</script>

<template>
  <button
    class="icon-btn"
    :class="[`icon-btn--${variant}`, `icon-btn--${size}`, { 'is-active': active }]"
    type="button"
    :title="title"
    :aria-label="label"
    :aria-pressed="active ? 'true' : undefined"
    :disabled="disabled"
  >
    <slot />
  </button>
</template>

<style scoped>
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: var(--radius-small);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard);
}

.icon-btn--md {
  width: 32px;
  height: 32px;
}

.icon-btn--sm {
  width: 28px;
  height: 28px;
}

.icon-btn:hover:not(:disabled) {
  background: var(--surface-secondary);
  color: var(--text-primary);
}

.icon-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.icon-btn--soft {
  background: var(--surface-secondary);
}

.icon-btn--soft:hover:not(:disabled) {
  background: var(--primary-soft);
  color: var(--primary);
}

.icon-btn--danger:hover:not(:disabled) {
  background: var(--error-soft);
  color: var(--error);
}

.icon-btn.is-active {
  background: var(--primary-soft);
  color: var(--primary);
}
</style>
