<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import AppSidebar from './AppSidebar.vue'
import ToastHost from './ToastHost.vue'
import { useUiStore } from '../stores/ui'

const ui = useUiStore()
const route = useRoute()
const { t } = useI18n()

/** The drawer (and its scrim) only exist below the sidebar breakpoint. */
const DRAWER_BREAKPOINT = 820
const isDrawerWidth = ref(false)

function syncViewport(): void {
  isDrawerWidth.value = window.innerWidth <= DRAWER_BREAKPOINT
  if (!isDrawerWidth.value) ui.closeSidebar()
}

onMounted(() => {
  syncViewport()
  window.addEventListener('resize', syncViewport)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncViewport)
})

watch(() => route.fullPath, () => ui.closeSidebar())
watchEffect(() => {
  document.title = t('common.appName')
})
</script>

<template>
  <div class="shell">
    <AppSidebar />

    <Transition name="scrim">
      <div v-if="isDrawerWidth && ui.sidebarOpen" class="shell__scrim" @click="ui.closeSidebar()" />
    </Transition>

    <main class="shell__main">
      <slot />
    </main>

    <ToastHost />
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  background: var(--background);
}

.shell__main {
  margin-inline-start: var(--sidebar-width);
  padding: var(--space-6) var(--space-8) var(--space-8);
  min-height: 100vh;
}

.shell__scrim {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: var(--overlay);
}

.scrim-enter-active,
.scrim-leave-active {
  transition: opacity var(--duration-base) var(--ease-standard);
}

.scrim-enter-from,
.scrim-leave-to {
  opacity: 0;
}

@media (max-width: 820px) {
  .shell__main {
    margin-inline-start: 0;
    padding: var(--space-4) var(--space-4) var(--space-8);
  }
}

@media (min-width: 1600px) {
  .shell__main {
    padding-inline: var(--space-8);
  }
}
</style>
