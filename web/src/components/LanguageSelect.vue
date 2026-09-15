<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import IconGlobe from './icons/IconGlobe.vue'
import { useUiStore } from '../stores/ui'
import type { LocalePreference } from '../i18n'

const ui = useUiStore()
const { t } = useI18n()

const options: { value: LocalePreference; labelKey: string }[] = [
  { value: 'system', labelKey: 'language.system' },
  { value: 'en-US', labelKey: 'language.en' },
  { value: 'zh-CN', labelKey: 'language.zh' },
]

function onChange(event: Event): void {
  ui.setLocale((event.target as HTMLSelectElement).value as LocalePreference)
}
</script>

<template>
  <label class="language">
    <span class="language__icon" aria-hidden="true"><IconGlobe /></span>
    <span class="visually-hidden">{{ t('a11y.languageSelect') }}</span>
    <select class="language__select" :value="ui.locale" @change="onChange">
      <option v-for="option in options" :key="option.value" :value="option.value">
        {{ t(option.labelKey) }}
      </option>
    </select>
  </label>
</template>

<style scoped>
.language {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-2) 0 var(--space-2);
  background: var(--surface-secondary);
  border-radius: var(--radius-small);
}

.language__icon {
  display: inline-flex;
  color: var(--text-muted);
}

.language__select {
  flex: 1;
  min-width: 0;
  height: 32px;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  font-size: var(--text-sm);
  font-weight: 500;
  cursor: pointer;
  appearance: none;
}

.language__select:focus {
  outline: none;
}
</style>
