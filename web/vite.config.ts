import { fileURLToPath, URL } from 'node:url'
import { readFileSync } from 'node:fs'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// The UI version is the package version, injected at build time: bumping
// web/package.json is enough, nothing in the components hardcodes it.
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as {
  version: string
}

export default defineConfig({
  base: '/',
  plugins: [vue()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  server: {
    port: 5173,
    strictPort: false,
    // `..` lets vitest load ../scripts/check-i18n.mjs, which lives outside this Vite root.
    fs: { allow: ['.', '..'] },
    proxy: {
      '/api': {
        target: 'http://localhost:6868',
        changeOrigin: true,
        ws: true,
      },
    },
  },
  preview: {
    port: 4173,
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.ts'],
    globals: false,
    restoreMocks: true,
    server: {
      deps: {
        inline: [/check-i18n/],
      },
    },
  },
})
