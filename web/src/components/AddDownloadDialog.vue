<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppButton from './AppButton.vue'
import ConnectionStepper from './ConnectionStepper.vue'
import EmptyState from './EmptyState.vue'
import IconButton from './IconButton.vue'
import IconChevronDown from './icons/IconChevronDown.vue'
import IconChevronRight from './icons/IconChevronRight.vue'
import IconClose from './icons/IconClose.vue'
import IconFolder from './icons/IconFolder.vue'
import IconPlus from './icons/IconPlus.vue'
import IconRefresh from './icons/IconRefresh.vue'
import IconTrash from './icons/IconTrash.vue'
import { api, toApiError } from '../api/client'
import { useBodyScrollLock } from '../composables/useBodyScrollLock'
import { useFormat } from '../composables/useFormat'
import { useDownloadsStore } from '../stores/downloads'
import { useSettingsStore } from '../stores/settings'
import { useUiStore } from '../stores/ui'
import type { CreateDownload, DirectoryListing, Task } from '../api/types'

const props = defineProps<{ open: boolean }>()

const emit = defineEmits<{
  close: []
  created: [task: Task]
}>()

const { t } = useI18n()
const fmt = useFormat()
const ui = useUiStore()
const downloads = useDownloadsStore()
const settings = useSettingsStore()

interface HeaderRow {
  name: string
  value: string
}

const form = reactive({
  url: '',
  fileName: '',
  folder: '',
  connections: 8,
  cookies: '',
  referer: '',
  userAgent: '',
  proxy: '',
  speedLimit: 0,
  checksum: '',
  hls: false,
  overwrite: false,
  startImmediately: true,
})

const headerRows = ref<HeaderRow[]>([])
const advancedOpen = ref(false)
const submitting = ref(false)
const urlError = ref<string | null>(null)
const urlInput = ref<HTMLInputElement | null>(null)

const pickerOpen = ref(false)
const pickerLoading = ref(false)
const pickerError = ref<string | null>(null)
const listing = ref<DirectoryListing | null>(null)

const open = computed(() => props.open)
useBodyScrollLock(open)

const maxConnections = computed(() => settings.maxConnections)
const speedLimitLabel = computed(() =>
  form.speedLimit > 0 ? fmt.speed(form.speedLimit) : t('common.unlimited'),
)

async function loadDirectory(path?: string): Promise<void> {
  pickerLoading.value = true
  pickerError.value = null
  try {
    listing.value = await api.listDirectories(path)
  } catch (caught) {
    pickerError.value = toApiError(caught).messageKey
    listing.value = null
  } finally {
    pickerLoading.value = false
  }
}

function openPicker(): void {
  pickerOpen.value = true
  void loadDirectory(form.folder || settings.defaultFolder || undefined)
}

function useCurrentFolder(): void {
  if (!listing.value) return
  form.folder = listing.value.path
  pickerOpen.value = false
}

function addHeaderRow(): void {
  headerRows.value = [...headerRows.value, { name: '', value: '' }]
}

function removeHeaderRow(index: number): void {
  headerRows.value = headerRows.value.filter((_, position) => position !== index)
}

function resetForm(): void {
  form.url = ''
  form.fileName = ''
  form.folder = settings.settings?.defaultFolder ?? ''
  form.connections = settings.defaultConnections
  form.cookies = ''
  form.referer = ''
  form.userAgent = ''
  form.proxy = ''
  form.speedLimit = 0
  form.checksum = ''
  form.hls = false
  form.overwrite = false
  form.startImmediately = true
  headerRows.value = []
  advancedOpen.value = false
  urlError.value = null
  pickerOpen.value = false
  listing.value = null
}

function collectHeaders(): Record<string, string> | undefined {
  const entries = headerRows.value
    .map((row) => [row.name.trim(), row.value] as const)
    .filter(([name]) => name.length > 0)
  if (entries.length === 0) return undefined
  return Object.fromEntries(entries)
}

function validate(): boolean {
  const url = form.url.trim()
  if (url.length === 0) {
    urlError.value = 'add.urlRequired'
    return false
  }
  if (!/^https?:\/\/\S+$/i.test(url)) {
    urlError.value = 'add.urlInvalid'
    return false
  }
  urlError.value = null
  return true
}

function buildPayload(): CreateDownload {
  const payload: CreateDownload = { url: form.url.trim() }

  const fileName = form.fileName.trim()
  if (fileName) payload.fileName = fileName
  const folder = form.folder.trim()
  if (folder) payload.folder = folder
  payload.connections = form.connections
  const headers = collectHeaders()
  if (headers) payload.headers = headers
  if (form.cookies.trim()) payload.cookies = form.cookies.trim()
  if (form.referer.trim()) payload.referer = form.referer.trim()
  if (form.userAgent.trim()) payload.userAgent = form.userAgent.trim()
  if (form.proxy.trim()) payload.proxy = form.proxy.trim()
  if (form.speedLimit > 0) payload.speedLimit = form.speedLimit
  if (form.checksum.trim()) payload.checksum = form.checksum.trim()
  payload.hls = form.hls
  payload.overwrite = form.overwrite
  payload.startImmediately = form.startImmediately
  return payload
}

async function submit(): Promise<void> {
  if (submitting.value) return
  if (!validate()) return
  submitting.value = true
  try {
    const task = await downloads.create(buildPayload())
    ui.pushToast({
      kind: 'success',
      key: 'toast.downloadAdded',
      params: { name: task.fileName },
    })
    emit('created', task)
    emit('close')
  } catch (caught) {
    const error = toApiError(caught)
    if (error.code === 'INVALID_URL' || error.code === 'UNSUPPORTED_SCHEME') {
      urlError.value = error.messageKey
    } else {
      ui.pushError(error)
    }
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.open,
  async (value) => {
    if (!value) return
    if (!settings.loaded) void settings.load()
    resetForm()
    await nextTick()
    urlInput.value?.focus()
  },
)
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="open" class="overlay">
        <div class="overlay__scrim" @click="emit('close')" />

        <div class="dialog" role="dialog" aria-modal="true" :aria-label="t('add.title')">
          <header class="dialog__header">
            <div>
              <h2 class="dialog__title">{{ t('add.title') }}</h2>
              <p class="dialog__description">{{ t('add.description') }}</p>
            </div>
            <IconButton :label="t('a11y.closeDialog')" @click="emit('close')">
              <IconClose />
            </IconButton>
          </header>

          <form class="dialog__body" @submit.prevent="submit">
            <div class="field">
              <label class="field__label" for="add-url">{{ t('add.url') }}</label>
              <input
                id="add-url"
                ref="urlInput"
                v-model="form.url"
                class="input"
                type="text"
                inputmode="url"
                autocomplete="off"
                :placeholder="t('add.urlPlaceholder')"
                :aria-invalid="urlError ? 'true' : undefined"
                @input="urlError = null"
              />
              <p v-if="urlError" class="field__error">{{ t(urlError) }}</p>
            </div>

            <div class="grid">
              <div class="field">
                <label class="field__label" for="add-file-name">{{ t('add.fileName') }}</label>
                <input
                  id="add-file-name"
                  v-model="form.fileName"
                  class="input"
                  type="text"
                  autocomplete="off"
                  :placeholder="t('add.fileNamePlaceholder')"
                />
                <p class="field__hint">{{ t('add.fileNameHint') }}</p>
              </div>

              <div class="field">
                <label class="field__label" for="add-folder">{{ t('add.location') }}</label>
                <div class="inline">
                  <input
                    id="add-folder"
                    v-model="form.folder"
                    class="input"
                    type="text"
                    autocomplete="off"
                    :placeholder="t('add.locationPlaceholder')"
                  />
                  <AppButton variant="secondary" type="button" @click="openPicker">
                    <IconFolder />
                    <span>{{ t('add.browse') }}</span>
                  </AppButton>
                </div>
              </div>
            </div>

            <div v-if="pickerOpen" class="picker">
              <header class="picker__header">
                <span class="picker__path mono truncate">{{ listing?.path ?? t('common.loading') }}</span>
                <div class="picker__header-actions">
                  <IconButton
                    :label="t('common.refresh')"
                    size="sm"
                    @click="loadDirectory(listing?.path)"
                  >
                    <IconRefresh />
                  </IconButton>
                  <IconButton
                    :label="t('common.close')"
                    size="sm"
                    @click="pickerOpen = false"
                  >
                    <IconClose />
                  </IconButton>
                </div>
              </header>

              <div class="picker__list" role="group" :aria-label="t('a11y.directoryList')">
                <p v-if="pickerLoading" class="picker__hint">{{ t('add.loadingDirectories') }}</p>
                <p v-else-if="pickerError" class="picker__error">{{ t(pickerError) }}</p>
                <template v-else-if="listing">
                  <button
                    v-if="listing.parent"
                    class="picker__item"
                    type="button"
                    @click="loadDirectory(listing.parent)"
                  >
                    <IconChevronRight class="picker__item-icon picker__item-icon--up" />
                    <span>{{ t('add.parentFolder') }}</span>
                  </button>
                  <button
                    v-for="entry in listing.directories"
                    :key="entry.path"
                    class="picker__item"
                    type="button"
                    @click="loadDirectory(entry.path)"
                  >
                    <IconFolder class="picker__item-icon" />
                    <span class="truncate">{{ entry.name }}</span>
                  </button>
                  <EmptyState
                    v-if="!listing.parent && listing.directories.length === 0"
                    compact
                    :title="t('empty.directoriesTitle')"
                    :description="t('empty.directoriesDescription')"
                  />
                </template>
              </div>

              <footer class="picker__footer">
                <AppButton variant="secondary" type="button" :disabled="!listing" @click="useCurrentFolder">
                  {{ t('add.useThisFolder') }}
                </AppButton>
              </footer>
            </div>

            <div class="field">
              <span class="field__label">{{ t('add.connections') }}</span>
              <ConnectionStepper
                v-model="form.connections"
                :max="maxConnections"
                :label="t('add.connections')"
              />
              <p class="field__hint">{{ t('add.connectionsHint') }}</p>
            </div>

            <button
              class="advanced"
              type="button"
              :aria-expanded="advancedOpen ? 'true' : 'false'"
              @click="advancedOpen = !advancedOpen"
            >
              <component :is="advancedOpen ? IconChevronDown : IconChevronRight" />
              <span>{{ t('add.advanced') }}</span>
            </button>

            <div v-if="advancedOpen" class="advanced__body">
              <div class="field">
                <span class="field__label">{{ t('add.headers') }}</span>
                <div class="headers">
                  <div v-for="(row, index) in headerRows" :key="index" class="headers__row">
                    <input
                      v-model="row.name"
                      class="input"
                      type="text"
                      autocomplete="off"
                      :placeholder="t('add.headerName')"
                      :aria-label="t('add.headerName')"
                    />
                    <input
                      v-model="row.value"
                      class="input"
                      type="text"
                      autocomplete="off"
                      :placeholder="t('add.headerValue')"
                      :aria-label="t('add.headerValue')"
                    />
                    <IconButton
                      :label="t('add.removeHeader')"
                      variant="danger"
                      @click="removeHeaderRow(index)"
                    >
                      <IconTrash />
                    </IconButton>
                  </div>
                  <p v-if="headerRows.length === 0" class="field__hint">{{ t('add.noHeaders') }}</p>
                  <AppButton variant="ghost" size="sm" type="button" @click="addHeaderRow">
                    <IconPlus />
                    <span>{{ t('add.addHeader') }}</span>
                  </AppButton>
                </div>
              </div>

              <div class="grid">
                <div class="field">
                  <label class="field__label" for="add-cookies">{{ t('add.cookies') }}</label>
                  <input
                    id="add-cookies"
                    v-model="form.cookies"
                    class="input"
                    type="text"
                    autocomplete="off"
                    :placeholder="t('add.cookiesPlaceholder')"
                  />
                </div>

                <div class="field">
                  <label class="field__label" for="add-referer">{{ t('add.referer') }}</label>
                  <input
                    id="add-referer"
                    v-model="form.referer"
                    class="input"
                    type="text"
                    autocomplete="off"
                    :placeholder="t('add.refererPlaceholder')"
                  />
                </div>

                <div class="field">
                  <label class="field__label" for="add-user-agent">{{ t('add.userAgent') }}</label>
                  <input
                    id="add-user-agent"
                    v-model="form.userAgent"
                    class="input"
                    type="text"
                    autocomplete="off"
                    :placeholder="t('add.userAgentPlaceholder')"
                  />
                </div>

                <div class="field">
                  <label class="field__label" for="add-proxy">{{ t('add.proxy') }}</label>
                  <input
                    id="add-proxy"
                    v-model="form.proxy"
                    class="input"
                    type="text"
                    autocomplete="off"
                    :placeholder="t('add.proxyPlaceholder')"
                  />
                </div>

                <div class="field">
                  <label class="field__label" for="add-speed-limit">{{ t('add.speedLimit') }}</label>
                  <input
                    id="add-speed-limit"
                    v-model.number="form.speedLimit"
                    class="input"
                    type="number"
                    min="0"
                    step="1024"
                  />
                  <p class="field__hint">{{ t('add.speedLimitHint', { value: speedLimitLabel }) }}</p>
                </div>

                <div class="field">
                  <label class="field__label" for="add-checksum">{{ t('add.checksum') }}</label>
                  <input
                    id="add-checksum"
                    v-model="form.checksum"
                    class="input mono"
                    type="text"
                    autocomplete="off"
                    :placeholder="t('add.checksumPlaceholder')"
                  />
                  <p class="field__hint">{{ t('add.checksumHint') }}</p>
                </div>
              </div>

              <div class="switches">
                <label class="checkbox" :title="t('add.hlsHint')">
                  <input v-model="form.hls" type="checkbox" />
                  <span>{{ t('add.hls') }}</span>
                </label>
                <label class="checkbox" :title="t('add.overwriteHint')">
                  <input v-model="form.overwrite" type="checkbox" />
                  <span>{{ t('add.overwrite') }}</span>
                </label>
                <label class="checkbox" :title="t('add.startImmediatelyHint')">
                  <input v-model="form.startImmediately" type="checkbox" />
                  <span>{{ t('add.startImmediately') }}</span>
                </label>
              </div>
            </div>
          </form>

          <footer class="dialog__footer">
            <AppButton variant="ghost" @click="emit('close')">{{ t('common.cancel') }}</AppButton>
            <AppButton :loading="submitting" @click="submit">{{ t('add.submit') }}</AppButton>
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
  z-index: 65;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: var(--space-8) var(--space-4);
  overflow-y: auto;
}

.overlay__scrim {
  position: fixed;
  inset: 0;
  background: var(--overlay);
  backdrop-filter: blur(2px);
}

.dialog {
  position: relative;
  width: min(680px, 100%);
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-dialog);
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - var(--space-8) * 2);
}

.dialog__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-5) var(--space-5) var(--space-4);
  border-bottom: 1px solid var(--border);
}

.dialog__title {
  font-size: var(--text-lg);
  font-weight: 650;
}

.dialog__description {
  margin-top: 2px;
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.dialog__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-5);
  overflow-y: auto;
}

.dialog__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: var(--space-4) var(--space-5);
  border-top: 1px solid var(--border);
}

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
}

.inline {
  display: flex;
  gap: var(--space-2);
}

.field__error {
  font-size: var(--text-xs);
  color: var(--error);
}

.picker {
  border: 1px solid var(--border);
  border-radius: var(--radius-medium);
  background: var(--surface-secondary);
  overflow: hidden;
}

.picker__header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
}

.picker__path {
  flex: 1;
  font-size: var(--text-xs);
  color: var(--text-secondary);
}

.picker__header-actions {
  display: flex;
  gap: var(--space-1);
}

.picker__list {
  max-height: 200px;
  overflow-y: auto;
  padding: var(--space-1);
  display: flex;
  flex-direction: column;
}

.picker__item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: transparent;
  border: 0;
  border-radius: var(--radius-small);
  color: var(--text-primary);
  font-size: var(--text-sm);
  text-align: left;
  cursor: pointer;
}

.picker__item:hover {
  background: var(--surface);
}

.picker__item-icon {
  color: var(--text-muted);
  flex-shrink: 0;
}

.picker__item-icon--up {
  transform: rotate(-90deg);
}

.picker__hint,
.picker__error {
  padding: var(--space-3);
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.picker__error {
  color: var(--error);
}

.picker__footer {
  display: flex;
  justify-content: flex-end;
  padding: var(--space-2) var(--space-3);
  border-top: 1px solid var(--border);
}

.advanced {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0;
  background: transparent;
  border: 0;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  align-self: flex-start;
}

.advanced:hover {
  color: var(--text-primary);
}

.advanced__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-medium);
}

.headers {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  align-items: flex-start;
}

.headers__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr) 32px;
  gap: var(--space-2);
  width: 100%;
  align-items: center;
}

.switches {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity var(--duration-base) var(--ease-standard);
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}

@media (max-width: 820px) {
  .overlay {
    padding: 0;
  }

  .dialog {
    width: 100%;
    max-height: 100vh;
    min-height: 100vh;
    border-radius: 0;
    border: 0;
  }

  .grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .inline {
    flex-direction: column;
  }
}
</style>
