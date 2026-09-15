# Download Manager — Web UI

Vue 3 + TypeScript + Vite frontend for the self-hosted download manager server.
The wire contract lives in [`../docs/api.md`](../docs/api.md) and is mirrored by
`src/api/types.ts`.

> This is an **independent project**. It is not affiliated with, endorsed by, or
> connected to AB Download Manager, and it ships no ABDM logo or brand assets.

## Commands

```bash
npm install
npm run dev          # dev server on :5173, proxies /api (and the WS) to :6868
npm run build        # -> web/dist
npm run preview      # serve the production build
npm run lint         # ESLint 9 flat config (vue + ts + no-bare-strings-in-template)
npm run typecheck    # vue-tsc --noEmit
npm run test         # vitest run (jsdom)
npm run i18n:check   # locale catalogue check (node ../scripts/check-i18n.mjs)
```

## Layout

```
src/
  api/        types.ts (wire contract), client.ts (fetch + ApiError), ws.ts (/api/v1/events)
  components/ AppShell, AppSidebar, DownloadCard, ... plus icons/Icon*.vue
  i18n/       vue-i18n instance + message helpers
  locales/    en-US.json, zh-CN.json (must stay key-identical)
  pages/      Downloads, History, Settings, About
  stores/     downloads, settings, ui (Pinia)
  styles/     tokens.css (design tokens, the only place hex colours live), base.css
  utils/      format.ts (Intl based formatting), storage.ts
tests/        vitest specs: format, downloads store reducer, i18n checker
```

## Theming and i18n

* `data-theme` on `<html>` is always resolved (`light` / `dark`); `system`
  follows `prefers-color-scheme` live.
* UI preferences live in `localStorage`: `ui.theme`, `ui.locale`, `ui.units`.
  Language and theme are **web** preferences, never server settings.
* Every user visible string goes through `t()`; `errors.<CODE>` covers the full
  error catalogue from `docs/api.md` in both locales.
