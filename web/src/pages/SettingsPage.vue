<script setup lang="ts">
import { computed, onMounted, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppButton from '../components/AppButton.vue'
import ConnectionStepper from '../components/ConnectionStepper.vue'
import EmptyState from '../components/EmptyState.vue'
import IconSettings from '../components/icons/IconSettings.vue'
import LanguageSelect from '../components/LanguageSelect.vue'
import PageHeader from '../components/PageHeader.vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useFormat } from '../composables/useFormat'
import { useSettingsStore } from '../stores/settings'
import { useUiStore } from '../stores/ui'
import type { ServerSettings } from '../api/types'
import type { UnitSystem } from '../utils/format'

const { t } = useI18n()
const fmt = useFormat()
const settings = useSettingsStore()
const ui = useUiStore()

type Draft = Pick<
  ServerSettings,
  | 'downloadRoot'
  | 'defaultFolder'
  | 'defaultConnections'
  | 'maxConcurrentDownloads'
  | 'globalSpeedLimit'
  | 'resumeOnStartup'
  | 'progressIntervalMs'
>

const draft = reactive<Draft>({
  downloadRoot: '',
  defaultFolder: '',
  defaultConnections: 8,
  maxConcurrentDownloads: 3,
  globalSpeedLimit: 0,
  resumeOnStartup: true,
  progressIntervalMs: 500,
})

onMounted(() => {
  void settings.load()
})

watch(
  () => settings.settings,
  (value) => {
    if (!value) return
    draft.downloadRoot = value.downloadRoot
    draft.defaultFolder = value.defaultFolder
    draft.defaultConnections = value.defaultConnections
    draft.maxConcurrentDownloads = value.maxConcurrentDownloads
    draft.globalSpeedLimit = value.globalSpeedLimit
    draft.resumeOnStartup = value.resumeOnStartup
    draft.progressIntervalMs = value.progressIntervalMs
  },
  { immediate: true },
)

const loaded = computed(() => settings.settings !== null)
const dirty = computed(() => {
  const current = settings.settings
  if (!current) return false
  return (
    current.downloadRoot !== draft.downloadRoot ||
    current.defaultFolder !== draft.defaultFolder ||
    current.defaultConnections !== draft.defaultConnections ||
    current.maxConcurrentDownloads !== draft.maxConcurrentDownloads ||
    current.globalSpeedLimit !== draft.globalSpeedLimit ||
    current.resumeOnStartup !== draft.resumeOnStartup ||
    current.progressIntervalMs !== draft.progressIntervalMs
  )
})

const limitLabel = computed(() =>
  draft.globalSpeedLimit > 0 ? fmt.speed(draft.globalSpeedLimit) : t('common.unlimited'),
)

const unitsOptions: { value: UnitSystem; labelKey: string; hintKey: string }[] = [
  { value: 'binary', labelKey: 'units.binary', hintKey: 'units.binaryHint' },
  { value: 'decimal', labelKey: 'units.decimal', hintKey: 'units.decimalHint' },
]

function onUnitsChange(event: Event): void {
  ui.setUnits((event.target as HTMLSelectElement).value as UnitSystem)
}

function save(): void {
  void (async () => {
    try {
      await settings.save({ ...draft })
      ui.pushToast({ kind: 'success', key: 'toast.settingsSaved' })
    } catch (caught) {
      ui.pushError(caught, 'settings.saveFailed')
    }
  })()
}
</script>

<template>
  <section>
    <PageHeader :title="t('settings.title')" :subtitle="t('settings.subtitle')">
      <template #actions>
        <AppButton :loading="settings.saving" :disabled="!dirty || !loaded" @click="save">
          {{ t('settings.save') }}
        </AppButton>
      </template>
    </PageHeader>

    <EmptyState
      v-if="settings.error && !loaded"
      :title="t('empty.loadFailedTitle')"
      :description="t('empty.loadFailedDescription')"
    >
      <template #icon><IconSettings /></template>
      <template #actions>
        <AppButton variant="secondary" @click="settings.load(true)">{{ t('common.retry') }}</AppButton>
      </template>
    </EmptyState>

    <template v-else>
      <div class="card panel">
        <header class="panel__header">
          <h2 class="panel__title">{{ t('settings.serverSection') }}</h2>
          <p class="panel__hint">{{ t('settings.serverSectionHint') }}</p>
        </header>

        <div class="panel__body grid">
          <div class="field">
            <label class="field__label" for="set-root">{{ t('settings.downloadRoot') }}</label>
            <input id="set-root" v-model="draft.downloadRoot" class="input mono" type="text" />
            <p class="field__hint">{{ t('settings.downloadRootHint') }}</p>
          </div>

          <div class="field">
            <label class="field__label" for="set-folder">{{ t('settings.defaultFolder') }}</label>
            <input id="set-folder" v-model="draft.defaultFolder" class="input mono" type="text" />
            <p class="field__hint">{{ t('settings.defaultFolderHint') }}</p>
          </div>

          <div class="field">
            <span class="field__label">{{ t('settings.defaultConnections') }}</span>
            <ConnectionStepper
              v-model="draft.defaultConnections"
              :max="settings.maxConnections"
              :label="t('settings.defaultConnections')"
            />
            <p class="field__hint">{{ t('settings.defaultConnectionsHint') }}</p>
          </div>

          <div class="field">
            <label class="field__label" for="set-concurrent">
              {{ t('settings.maxConcurrentDownloads') }}
            </label>
            <input
              id="set-concurrent"
              v-model.number="draft.maxConcurrentDownloads"
              class="input"
              type="number"
              min="1"
              max="64"
            />
            <p class="field__hint">{{ t('settings.maxConcurrentDownloadsHint') }}</p>
          </div>

          <div class="field">
            <label class="field__label" for="set-limit">{{ t('settings.globalSpeedLimit') }}</label>
            <input
              id="set-limit"
              v-model.number="draft.globalSpeedLimit"
              class="input"
              type="number"
              min="0"
              step="1024"
            />
            <p class="field__hint">
              {{ t('settings.globalSpeedLimitHint', { value: limitLabel }) }}
            </p>
          </div>

          <div class="field">
            <label class="field__label" for="set-interval">{{ t('settings.progressIntervalMs') }}</label>
            <input
              id="set-interval"
              v-model.number="draft.progressIntervalMs"
              class="input"
              type="number"
              min="100"
              step="100"
            />
            <p class="field__hint">
              {{ t('settings.progressIntervalMsHint', { value: draft.progressIntervalMs }) }}
            </p>
          </div>

          <div class="field">
            <span class="field__label">{{ t('settings.resumeOnStartup') }}</span>
            <label class="checkbox">
              <input v-model="draft.resumeOnStartup" type="checkbox" />
              <span>{{ draft.resumeOnStartup ? t('common.enabled') : t('common.disabled') }}</span>
            </label>
            <p class="field__hint">{{ t('settings.resumeOnStartupHint') }}</p>
          </div>

          <div class="field">
            <span class="field__label">{{ t('settings.authMode') }}</span>
            <p class="value mono">{{ settings.settings?.authMode ?? t('common.notAvailable') }}</p>
            <p class="field__hint">{{ t('settings.authModeHint') }}</p>
          </div>

          <div class="field">
            <span class="field__label">{{ t('settings.maxConnections') }}</span>
            <p class="value mono">{{ settings.maxConnections }}</p>
            <p class="field__hint">{{ t('settings.maxConnectionsHint') }}</p>
          </div>
        </div>
      </div>

      <div class="card panel">
        <header class="panel__header">
          <h2 class="panel__title">{{ t('settings.preferencesSection') }}</h2>
          <p class="panel__hint">{{ t('settings.preferencesSectionHint') }}</p>
        </header>

        <div class="panel__body grid">
          <div class="field">
            <span class="field__label">{{ t('settings.language') }}</span>
            <LanguageSelect />
            <p class="field__hint">{{ t('settings.languageHint') }}</p>
          </div>

          <div class="field">
            <span class="field__label">{{ t('settings.theme') }}</span>
            <ThemeToggle />
            <p class="field__hint">{{ t('settings.themeHint') }}</p>
          </div>

          <div class="field">
            <label class="field__label" for="set-units">{{ t('settings.units') }}</label>
            <select
              id="set-units"
              class="select"
              :value="ui.units"
              :aria-label="t('a11y.unitsSelect')"
              @change="onUnitsChange"
            >
              <option v-for="option in unitsOptions" :key="option.value" :value="option.value">
                {{ t(option.labelKey) }} · {{ t(option.hintKey) }}
              </option>
            </select>
            <p class="field__hint">{{ t('settings.unitsHint') }}</p>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.panel + .panel {
  margin-top: var(--space-4);
}

.panel__header {
  padding: var(--space-5) var(--space-5) var(--space-4);
  border-bottom: 1px solid var(--border);
}

.panel__title {
  font-size: var(--text-lg);
  font-weight: 650;
}

.panel__hint {
  margin-top: 2px;
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.panel__body {
  padding: var(--space-5);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: var(--space-5);
}

.value {
  font-size: var(--text-sm);
  color: var(--text-primary);
}
</style>
