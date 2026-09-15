import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'downloads',
    component: () => import('../pages/DownloadsPage.vue'),
    meta: { titleKey: 'nav.downloads' },
  },
  {
    path: '/history',
    name: 'history',
    component: () => import('../pages/HistoryPage.vue'),
    meta: { titleKey: 'nav.history' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('../pages/SettingsPage.vue'),
    meta: { titleKey: 'nav.settings' },
  },
  {
    path: '/about',
    name: 'about',
    component: () => import('../pages/AboutPage.vue'),
    meta: { titleKey: 'nav.about' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

export default router
