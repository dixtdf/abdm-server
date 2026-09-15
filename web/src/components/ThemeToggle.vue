<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import IconMonitor from './icons/IconMonitor.vue'
import IconMoon from './icons/IconMoon.vue'
import IconSun from './icons/IconSun.vue'
import { useUiStore, type ThemePreference } from '../stores/ui'

const ui = useUiStore()
const { t } = useI18n()

const options = computed<
  { value: ThemePreference; icon: typeof IconSun; label: string }[]
>(() => [
  { value: 'system', icon: IconMonitor, label: t('theme.system') },
  { value: 'light', icon: IconSun, label: t('theme.light') },
  { value: 'dark', icon: IconMoon, label: t('theme.dark') },
])
</script>

<template>
  <div class="theme" role="group" :aria-label="t('theme.label')">
    <button
      v-for="option in options"
      :key="option.value"
      class="theme__option"
      :class="{ 'is-active': ui.theme === option.value }"
      type="button"
      :title="t('a11y.themeOption', { name: option.label })"
      :aria-label="option.label"
      :aria-pressed="ui.theme === option.value ? 'true' : 'false'"
      @click="ui.setTheme(option.value)"
    >
      <component :is="option.icon" />
    </button>
  </div>
</template>

<style scoped>
.theme {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  background: var(--surface-secondary);
  border-radius: var(--radius-small);
}

.theme__option {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 28px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.theme__option:hover {
  color: var(--text-primary);
}

.theme__option.is-active {
  background: var(--surface);
  color: var(--primary);
  box-shadow: var(--shadow-card);
}
</style>
