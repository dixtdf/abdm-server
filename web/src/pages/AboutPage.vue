<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppButton from '../components/AppButton.vue'
import EmptyState from '../components/EmptyState.vue'
import IconInfo from '../components/icons/IconInfo.vue'
import PageHeader from '../components/PageHeader.vue'
import { api, toApiError } from '../api/client'
import { useFormat } from '../composables/useFormat'
import type { ApiError } from '../api/client'
import type { VersionResponse } from '../api/types'

const { t } = useI18n()
const fmt = useFormat()

/** Injected by vite.config.ts from `web/package.json` (see `__APP_VERSION__` in env.d.ts). */
const WEB_UI_VERSION = __APP_VERSION__

const version = ref<VersionResponse | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)

const fallback = computed(() => t('common.notAvailable'))

async function load(): Promise<void> {
  loading.value = true
  try {
    version.value = await api.version()
    error.value = null
  } catch (caught) {
    error.value = toApiError(caught)
    version.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)

const projectRows = computed(() => [
  { label: t('about.projectVersion'), value: WEB_UI_VERSION, mono: true },
  { label: t('about.apiVersion'), value: version.value?.apiVersion ?? fallback.value, mono: true },
])

const engineRows = computed(() => [
  { label: t('about.engine'), value: version.value?.engine ?? fallback.value, mono: true },
  { label: t('about.engineVersion'), value: version.value?.engineVersion ?? fallback.value, mono: true },
  { label: t('about.java'), value: version.value?.java ?? fallback.value, mono: true },
  { label: t('about.os'), value: version.value?.os ?? fallback.value, mono: true },
  { label: t('about.startedAt'), value: fmt.dateTime(version.value?.startedAt), mono: false },
])

const compatRows = computed(() => [
  { label: t('about.abdmVersion'), value: version.value?.abdmVersion ?? fallback.value, mono: true },
  { label: t('about.abdmCommit'), value: version.value?.abdmCommit ?? fallback.value, mono: true },
])
</script>

<template>
  <section>
    <PageHeader :title="t('about.title')" :subtitle="t('about.subtitle')">
      <template #actions>
        <AppButton variant="secondary" :loading="loading" @click="load">
          {{ t('common.refresh') }}
        </AppButton>
      </template>
    </PageHeader>

    <EmptyState
      v-if="error"
      :title="t('empty.loadFailedTitle')"
      :description="t('empty.loadFailedDescription')"
    >
      <template #icon><IconInfo /></template>
      <template #actions>
        <AppButton variant="secondary" @click="load">{{ t('common.retry') }}</AppButton>
      </template>
    </EmptyState>

    <template v-else>
      <div class="grid">
        <div class="card panel">
          <header class="panel__header">
            <h2 class="panel__title">{{ t('about.projectSection') }}</h2>
          </header>
          <dl class="rows">
            <div v-for="row in projectRows" :key="row.label" class="row">
              <dt class="row__label">{{ row.label }}</dt>
              <dd class="row__value" :class="{ mono: row.mono }">{{ row.value }}</dd>
            </div>
          </dl>
        </div>

        <div class="card panel">
          <header class="panel__header">
            <h2 class="panel__title">{{ t('about.serverSection') }}</h2>
          </header>
          <dl class="rows">
            <div v-for="row in engineRows" :key="row.label" class="row">
              <dt class="row__label">{{ row.label }}</dt>
              <dd class="row__value" :class="{ mono: row.mono }">{{ row.value }}</dd>
            </div>
          </dl>
        </div>

        <div class="card panel">
          <header class="panel__header">
            <h2 class="panel__title">{{ t('about.compatSection') }}</h2>
          </header>
          <dl class="rows">
            <div v-for="row in compatRows" :key="row.label" class="row">
              <dt class="row__label">{{ row.label }}</dt>
              <dd class="row__value" :class="{ mono: row.mono }">{{ row.value }}</dd>
            </div>
          </dl>
        </div>
        <div class="card panel">
          <header class="panel__header">
            <h2 class="panel__title">{{ t('about.licenseSection') }}</h2>
          </header>
          <dl class="rows">
            <div class="row">
              <dt class="row__label">{{ t('about.license') }}</dt>
              <dd class="row__value">{{ t('about.licenseValue') }}</dd>
            </div>
          </dl>
        </div>
      </div>

      <div class="card notice">
        <h2 class="notice__title">{{ t('about.disclaimerTitle') }}</h2>
        <p class="notice__text">{{ t('about.disclaimer') }}</p>
        <p class="notice__text notice__text--muted">{{ t('about.independent') }}</p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: var(--space-4);
}

.panel__header {
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--border);
}

.panel__title {
  font-size: var(--text-base);
  font-weight: 650;
}

.rows {
  padding: var(--space-2) var(--space-5) var(--space-4);
}

.row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border);
}

.row:last-child {
  border-bottom: 0;
}

.row__label {
  font-size: var(--text-xs);
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.row__value {
  font-size: var(--text-sm);
  color: var(--text-primary);
  text-align: end;
  word-break: break-all;
}

.notice {
  margin-top: var(--space-4);
  padding: var(--space-5);
  border-inline-start: 3px solid var(--primary);
}

.notice__title {
  font-size: var(--text-base);
  font-weight: 650;
}

.notice__text {
  margin-top: var(--space-2);
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.notice__text--muted {
  color: var(--text-muted);
}
</style>
