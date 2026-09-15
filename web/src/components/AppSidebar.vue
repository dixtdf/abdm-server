<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import IconButton from './IconButton.vue'
import IconClose from './icons/IconClose.vue'
import IconDownload from './icons/IconDownload.vue'
import IconHistory from './icons/IconHistory.vue'
import IconInfo from './icons/IconInfo.vue'
import IconLogo from './icons/IconLogo.vue'
import IconSettings from './icons/IconSettings.vue'
import LanguageSelect from './LanguageSelect.vue'
import ThemeToggle from './ThemeToggle.vue'
import { useDownloadsStore } from '../stores/downloads'
import { useUiStore } from '../stores/ui'

const ui = useUiStore()
const downloads = useDownloadsStore()
const route = useRoute()
const { t } = useI18n()

const items = [
  { to: '/', labelKey: 'nav.downloads', icon: IconDownload },
  { to: '/history', labelKey: 'nav.history', icon: IconHistory },
  { to: '/settings', labelKey: 'nav.settings', icon: IconSettings },
  { to: '/about', labelKey: 'nav.about', icon: IconInfo },
]

function isActive(target: string): boolean {
  return target === '/' ? route.path === '/' : route.path.startsWith(target)
}

const wsKey = computed(() => {
  if (downloads.wsStatus === 'open') return 'ws.live'
  if (downloads.wsStatus === 'connecting' || downloads.wsStatus === 'reconnecting') return 'ws.reconnecting'
  return 'ws.offline'
})
</script>

<template>
  <aside class="sidebar" :class="{ 'is-open': ui.sidebarOpen }">
    <div class="sidebar__brand">
      <span class="sidebar__logo" aria-hidden="true"><IconLogo /></span>
      <span class="sidebar__brand-text">
        <span class="sidebar__name">{{ t('common.appName') }}</span>
        <span class="sidebar__tagline">{{ t('common.appDescription') }}</span>
      </span>
      <IconButton
        class="sidebar__close"
        :label="t('a11y.closeSidebar')"
        size="sm"
        @click="ui.closeSidebar()"
      >
        <IconClose />
      </IconButton>
    </div>

    <nav class="sidebar__nav" :aria-label="t('a11y.mainNavigation')">
      <RouterLink
        v-for="item in items"
        :key="item.to"
        class="nav-item"
        :class="{ 'is-active': isActive(item.to) }"
        :to="item.to"
        :aria-current="isActive(item.to) ? 'page' : undefined"
      >
        <component :is="item.icon" />
        <span>{{ t(item.labelKey) }}</span>
      </RouterLink>
    </nav>

    <div class="sidebar__footer">
      <div
        class="sidebar__status"
        :class="`sidebar__status--${downloads.wsStatus}`"
        :title="t('ws.title', { status: t(wsKey) })"
      >
        <span class="sidebar__dot" aria-hidden="true" />
        <span class="sidebar__status-text">{{ t(wsKey) }}</span>
      </div>
      <div class="sidebar__prefs">
        <ThemeToggle />
        <LanguageSelect />
      </div>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  position: fixed;
  inset-block: 0;
  inset-inline-start: 0;
  z-index: 50;
  display: flex;
  flex-direction: column;
  width: var(--sidebar-width);
  padding: var(--space-5) var(--space-3);
  background: var(--surface);
  border-inline-end: 1px solid var(--border);
}

.sidebar__brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 0 var(--space-2) var(--space-5);
}

.sidebar__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-medium);
  background: var(--primary-soft);
  color: var(--primary);
  flex-shrink: 0;
}

.sidebar__brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.sidebar__name {
  font-size: var(--text-base);
  font-weight: 650;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar__tagline {
  font-size: var(--text-xs);
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar__close {
  display: none;
  margin-inline-start: auto;
}

.sidebar__nav {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  flex: 1;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 0 var(--space-3);
  min-height: 40px;
  border-radius: var(--radius-medium);
  color: var(--text-secondary);
  font-size: var(--text-base);
  font-weight: 550;
  transition: background var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.nav-item:hover {
  background: var(--surface-secondary);
  color: var(--text-primary);
}

.nav-item.is-active {
  background: var(--primary-soft);
  color: var(--primary);
}

.sidebar__footer {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding-top: var(--space-4);
  border-top: 1px solid var(--border);
}

.sidebar__status {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.sidebar__dot {
  width: 7px;
  height: 7px;
  border-radius: var(--radius-pill);
  background: var(--text-muted);
}

.sidebar__status--open .sidebar__dot {
  background: var(--success);
}

.sidebar__status--connecting .sidebar__dot,
.sidebar__status--reconnecting .sidebar__dot {
  background: var(--warning);
}

.sidebar__status--closed .sidebar__dot,
.sidebar__status--idle .sidebar__dot {
  background: var(--text-muted);
}

.sidebar__prefs {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

@media (max-width: 820px) {
  .sidebar {
    transform: translateX(-100%);
    transition: transform var(--duration-slow) var(--ease-standard);
    box-shadow: var(--shadow-dialog);
    width: min(280px, 84vw);
  }

  .sidebar.is-open {
    transform: translateX(0);
  }

  .sidebar__close {
    display: inline-flex;
  }
}
</style>
