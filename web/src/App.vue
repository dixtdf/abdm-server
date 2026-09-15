<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import AppShell from './components/AppShell.vue'
import { useDownloadsStore } from './stores/downloads'
import { useUiStore } from './stores/ui'

const downloads = useDownloadsStore()
const ui = useUiStore()

onMounted(() => {
  // Live updates arrive over the WebSocket; the REST list is only the seed.
  downloads.connect()
  void downloads.load()
})

onBeforeUnmount(() => {
  downloads.disconnect()
  ui.clearToasts()
})
</script>

<template>
  <AppShell>
    <RouterView />
  </AppShell>
</template>
