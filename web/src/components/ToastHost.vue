<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import IconAlert from './icons/IconAlert.vue'
import IconButton from './IconButton.vue'
import IconCheck from './icons/IconCheck.vue'
import IconClose from './icons/IconClose.vue'
import IconInfo from './icons/IconInfo.vue'
import { useUiStore, type ToastKind } from '../stores/ui'

const ui = useUiStore()
const { t } = useI18n()

const ICONS: Record<ToastKind, unknown> = {
  success: IconCheck,
  error: IconAlert,
  warning: IconAlert,
  info: IconInfo,
}

const iconFor = (kind: ToastKind): unknown => ICONS[kind] ?? IconInfo

const hasToasts = computed(() => ui.toasts.length > 0)
</script>

<template>
  <Teleport to="body">
    <div
      class="toasts"
      :class="{ 'toasts--empty': !hasToasts }"
      role="region"
      :aria-label="t('a11y.notifications')"
    >
      <TransitionGroup name="toast">
        <div
          v-for="toast in ui.toasts"
          :key="toast.id"
          class="toast"
          :class="`toast--${toast.kind}`"
          :role="toast.kind === 'error' ? 'alert' : 'status'"
        >
          <span class="toast__icon" aria-hidden="true">
            <component :is="iconFor(toast.kind)" />
          </span>
          <div class="toast__body">
            <p class="toast__message">{{ t(toast.key, toast.params ?? {}) }}</p>
            <p v-if="toast.detail" class="toast__detail">{{ toast.detail }}</p>
          </div>
          <IconButton :label="t('common.dismiss')" size="sm" @click="ui.dismissToast(toast.id)">
            <IconClose />
          </IconButton>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toasts {
  position: fixed;
  right: var(--space-5);
  bottom: var(--space-5);
  z-index: 80;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  width: min(360px, calc(100vw - var(--space-8)));
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-3);
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  border-left: 3px solid var(--text-muted);
  border-radius: var(--radius-medium);
  box-shadow: var(--shadow-dialog);
  pointer-events: auto;
}

.toast--success {
  border-left-color: var(--success);
}

.toast--error {
  border-left-color: var(--error);
}

.toast--warning {
  border-left-color: var(--warning);
}

.toast--info {
  border-left-color: var(--primary);
}

.toast__icon {
  display: inline-flex;
  margin-top: 4px;
  color: var(--text-secondary);
}

.toast--success .toast__icon {
  color: var(--success);
}

.toast--error .toast__icon {
  color: var(--error);
}

.toast--warning .toast__icon {
  color: var(--warning);
}

.toast--info .toast__icon {
  color: var(--primary);
}

.toast__body {
  flex: 1;
  min-width: 0;
}

.toast__message {
  font-size: var(--text-sm);
  font-weight: 550;
  color: var(--text-primary);
  word-break: break-word;
}

.toast__detail {
  margin-top: 2px;
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--text-muted);
  word-break: break-word;
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity var(--duration-base) var(--ease-standard),
    transform var(--duration-base) var(--ease-standard);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

@media (max-width: 820px) {
  .toasts {
    right: var(--space-4);
    left: var(--space-4);
    bottom: var(--space-4);
    width: auto;
  }
}
</style>
