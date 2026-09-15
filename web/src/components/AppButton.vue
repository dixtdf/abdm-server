<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
    size?: 'sm' | 'md'
    type?: 'button' | 'submit' | 'reset'
    disabled?: boolean
    loading?: boolean
    block?: boolean
  }>(),
  {
    variant: 'primary',
    size: 'md',
    type: 'button',
    disabled: false,
    loading: false,
    block: false,
  },
)
</script>

<template>
  <button
    class="btn"
    :class="[`btn--${variant}`, `btn--${size}`, { 'btn--block': block, 'btn--loading': loading }]"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading ? 'true' : undefined"
  >
    <span v-if="loading" class="btn__spinner" aria-hidden="true" />
    <span class="btn__label"><slot /></span>
  </button>
</template>

<style scoped>
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  border: 1px solid transparent;
  border-radius: var(--radius-small);
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard);
}

.btn--sm {
  min-height: 32px;
  padding: 0 var(--space-3);
  font-size: var(--text-sm);
}

.btn--md {
  min-height: 38px;
  padding: 0 var(--space-4);
  font-size: var(--text-base);
}

.btn--block {
  width: 100%;
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.btn--primary {
  background: var(--primary);
  color: var(--primary-foreground);
  box-shadow: 0 1px 2px var(--primary-soft);
}

.btn--primary:hover:not(:disabled) {
  background: var(--primary-hover);
}

.btn--secondary {
  background: var(--surface);
  color: var(--text-primary);
  border-color: var(--border);
}

.btn--secondary:hover:not(:disabled) {
  background: var(--surface-secondary);
}

.btn--ghost {
  background: transparent;
  color: var(--text-secondary);
}

.btn--ghost:hover:not(:disabled) {
  background: var(--surface-secondary);
  color: var(--text-primary);
}

.btn--danger {
  background: var(--error);
  color: var(--text-on-primary);
}

.btn--danger:hover:not(:disabled) {
  background: var(--error-hover);
}

.btn__spinner {
  width: 13px;
  height: 13px;
  border-radius: var(--radius-pill);
  border: 2px solid currentColor;
  border-top-color: transparent;
  animation: btn-spin 700ms linear infinite;
  flex: none;
}

/*
 * The label is its own flex row: icon + text are centred as one group and share the
 * button's gap. Without this the inline SVG sat on the text baseline, which pushed
 * the icon/text pair off centre (visible on "刷新" / "新建下载").
 */
.btn__label {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  line-height: 1;
}

.btn__label :deep(svg) {
  flex: none;
  display: block;
}

@keyframes btn-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
