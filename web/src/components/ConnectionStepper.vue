<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import IconButton from './IconButton.vue'
import IconMinus from './icons/IconMinus.vue'
import IconPlus from './icons/IconPlus.vue'

const props = withDefaults(
  defineProps<{
    modelValue: number
    min?: number
    max?: number
    /** Accessible name, e.g. `Connections for file.iso`. */
    label: string
    disabled?: boolean
    /** Preset chips, filtered down to `max`. */
    presets?: number[]
  }>(),
  {
    min: 1,
    max: 256,
    disabled: false,
    presets: () => [1, 2, 4, 8, 16, 32, 64, 128, 256],
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: number]
  /** Fired on every accepted change — callers patch the server immediately. */
  change: [value: number]
}>()

const { t } = useI18n()
const draft = ref(`${props.modelValue}`)

watch(
  () => props.modelValue,
  (value) => {
    draft.value = `${value}`
  },
)

const chips = computed(() => {
  const options = new Set(props.presets.filter((preset) => preset >= props.min && preset <= props.max))
  options.add(props.max)
  return [...options].sort((a, b) => a - b)
})

const canDecrease = computed(() => !props.disabled && props.modelValue > props.min)
const canIncrease = computed(() => !props.disabled && props.modelValue < props.max)

function commit(raw: number): void {
  if (props.disabled || !Number.isFinite(raw)) return
  const next = Math.min(props.max, Math.max(props.min, Math.round(raw)))
  if (next === props.modelValue) return
  emit('update:modelValue', next)
  emit('change', next)
}

function onInput(event: Event): void {
  const value = (event.target as HTMLInputElement).value
  draft.value = value
  const parsed = Number.parseInt(value, 10)
  if (Number.isFinite(parsed)) commit(parsed)
}

function onBlur(): void {
  draft.value = `${props.modelValue}`
}
</script>

<template>
  <div class="stepper" :class="{ 'is-disabled': disabled }">
    <IconButton
      :label="t('a11y.decreaseConnections')"
      size="sm"
      :disabled="!canDecrease"
      @click="commit(modelValue - 1)"
    >
      <IconMinus />
    </IconButton>

    <input
      class="stepper__value"
      type="number"
      inputmode="numeric"
      :min="min"
      :max="max"
      :value="draft"
      :disabled="disabled"
      :aria-label="label"
      @input="onInput"
      @blur="onBlur"
    />

    <IconButton
      :label="t('a11y.increaseConnections')"
      size="sm"
      :disabled="!canIncrease"
      @click="commit(modelValue + 1)"
    >
      <IconPlus />
    </IconButton>

    <div class="stepper__presets" role="group" :aria-label="t('download.connections')">
      <button
        v-for="chip in chips"
        :key="chip"
        class="stepper__chip"
        :class="{ 'is-active': chip === modelValue }"
        type="button"
        :disabled="disabled"
        :aria-pressed="chip === modelValue ? 'true' : 'false'"
        @click="commit(chip)"
      >
        {{ chip }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.stepper {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.stepper__value {
  width: 62px;
  height: 28px;
  padding: 0 var(--space-2);
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-small);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  -moz-appearance: textfield;
}

.stepper__value::-webkit-outer-spin-button,
.stepper__value::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.stepper__value:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.stepper__presets {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.stepper__chip {
  min-width: 30px;
  height: 24px;
  padding: 0 var(--space-2);
  background: var(--surface-secondary);
  color: var(--text-secondary);
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.stepper__chip:hover:not(:disabled) {
  background: var(--primary-soft-hover);
  color: var(--primary);
}

.stepper__chip.is-active {
  background: var(--primary);
  color: var(--primary-foreground);
}

.stepper__chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.stepper.is-disabled {
  opacity: 0.7;
}

@media (max-width: 820px) {
  .stepper__presets {
    width: 100%;
  }
}
</style>
