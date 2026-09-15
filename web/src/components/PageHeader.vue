<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import IconButton from './IconButton.vue'
import IconMenu from './icons/IconMenu.vue'
import { useUiStore } from '../stores/ui'

defineProps<{
  title: string
  subtitle?: string
}>()

const ui = useUiStore()
const { t } = useI18n()
</script>

<template>
  <header class="page-header">
    <IconButton
      class="page-header__menu"
      :label="t('a11y.openSidebar')"
      @click="ui.openSidebar()"
    >
      <IconMenu />
    </IconButton>

    <div class="page-header__titles">
      <h1 class="page-header__title">{{ title }}</h1>
      <p v-if="subtitle" class="page-header__subtitle">{{ subtitle }}</p>
    </div>

    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-height: var(--header-height);
  margin-bottom: var(--space-5);
}

.page-header__menu {
  display: none;
}

.page-header__titles {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}

.page-header__title {
  font-size: var(--text-2xl);
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--text-primary);
}

.page-header__subtitle {
  margin-top: 2px;
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

@media (max-width: 820px) {
  .page-header {
    flex-wrap: wrap;
    align-items: flex-start;
  }

  .page-header__menu {
    display: inline-flex;
  }

  .page-header__title {
    font-size: var(--text-xl);
  }

  .page-header__actions {
    width: 100%;
  }
}
</style>
