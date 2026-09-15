<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppButton from './AppButton.vue'
import IconButton from './IconButton.vue'
import IconClose from './icons/IconClose.vue'
import { useBodyScrollLock } from '../composables/useBodyScrollLock'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    message?: string
    confirmLabel?: string
    cancelLabel?: string
    danger?: boolean
    /** Optional secondary opt-in, e.g. “also delete the file from disk”. */
    extraOptionLabel?: string
    extraOptionChecked?: boolean
  }>(),
  {
    message: undefined,
    confirmLabel: undefined,
    cancelLabel: undefined,
    danger: false,
    extraOptionLabel: undefined,
    extraOptionChecked: false,
  },
)

const emit = defineEmits<{
  confirm: []
  cancel: []
  'update:extraOptionChecked': [value: boolean]
}>()

const { t } = useI18n()

const dialogRef = ref<HTMLElement | null>(null)
const confirmRef = ref<InstanceType<typeof AppButton> | null>(null)
const openRef = computed(() => props.open)
useBodyScrollLock(openRef)

const confirmText = computed(() => props.confirmLabel ?? t('common.confirm'))
const cancelText = computed(() => props.cancelLabel ?? t('common.cancel'))

watch(
  () => props.open,
  async (value) => {
    if (!value) return
    await nextTick()
    const button = confirmRef.value?.$el as HTMLElement | undefined
    button?.focus()
  },
)

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.stopPropagation()
    emit('cancel')
  }
}

function onToggleExtra(event: Event): void {
  emit('update:extraOptionChecked', (event.target as HTMLInputElement).checked)
}

function onConfirm(): void {
  emit('confirm')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="open" class="overlay" @keydown="onKeydown">
        <div class="overlay__scrim" @click="emit('cancel')" />
        <div
          ref="dialogRef"
          class="dialog"
          role="alertdialog"
          aria-modal="true"
          :aria-label="title"
        >
          <header class="dialog__header">
            <h2 class="dialog__title">{{ title }}</h2>
            <IconButton :label="t('a11y.closeDialog')" size="sm" @click="emit('cancel')">
              <IconClose />
            </IconButton>
          </header>

          <p v-if="message" class="dialog__message">{{ message }}</p>

          <label v-if="extraOptionLabel" class="checkbox dialog__option">
            <input
              type="checkbox"
              :checked="extraOptionChecked"
              @change="onToggleExtra"
            />
            <span>{{ extraOptionLabel }}</span>
          </label>

          <footer class="dialog__footer">
            <AppButton variant="ghost" @click="emit('cancel')">{{ cancelText }}</AppButton>
            <AppButton
              ref="confirmRef"
              :variant="danger ? 'danger' : 'primary'"
              @click="onConfirm"
            >
              {{ confirmText }}
            </AppButton>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
}

.overlay__scrim {
  position: absolute;
  inset: 0;
  background: var(--overlay);
  backdrop-filter: blur(2px);
}

.dialog {
  position: relative;
  width: min(420px, 100%);
  padding: var(--space-5);
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-dialog);
}

.dialog__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.dialog__title {
  font-size: var(--text-lg);
  font-weight: 650;
}

.dialog__message {
  margin-top: var(--space-3);
  color: var(--text-secondary);
  font-size: var(--text-base);
}

.dialog__option {
  margin-top: var(--space-4);
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.dialog__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-5);
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity var(--duration-base) var(--ease-standard);
}

.dialog-fade-enter-active .dialog,
.dialog-fade-leave-active .dialog {
  transition: transform var(--duration-base) var(--ease-standard);
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}

.dialog-fade-enter-from .dialog,
.dialog-fade-leave-to .dialog {
  transform: scale(0.97);
}
</style>
