import { DEFAULT_SETTINGS, cookieHeader, downloadUrl, fileName, normalizeSettings, serverOrigin, shouldIntercept } from "./core.js";

async function cookiesForDownload(api, url) {
  // Split incognito mode gives each service worker its own cookie store.
  return cookieHeader(await api.cookies.getAll({ url }));
}

async function submitDownload(fetcher, origin, token, body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10000);
  try {
    const response = await fetcher(`${origin}/api/v1/downloads`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (response.status !== 201) throw new Error(`http${response.status}`);
    const task = await response.json();
    if (!task.id) throw new Error("invalidServerResponse");
    return task.id;
  } finally {
    clearTimeout(timeout);
  }
}

async function rollback(fetcher, origin, token, id) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10000);
  try {
    const response = await fetcher(`${origin}/api/v1/downloads/${encodeURIComponent(id)}?deleteFile=true`, {
      method: "DELETE",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      signal: controller.signal,
    });
    if (response.status !== 204) throw new Error(`rollbackHttp${response.status}`);
  } finally {
    clearTimeout(timeout);
  }
}

async function safeReport(report, code, detail) {
  try { await report(code, detail); } catch { /* Reporting must never change a transfer. */ }
}

/** Handoff a Chrome download while filename determination holds completion. */
export async function interceptDownload(item, { api, fetcher = fetch, userAgent = "", report = () => {}, completionHeld = false }) {
  const stored = await api.storage.sync.get({ settings: DEFAULT_SETTINGS });
  const settings = normalizeSettings(stored.settings);
  if (!settings.enabled || !settings.host) return "disabled";

  let origin;
  try { origin = serverOrigin(settings); } catch { return "invalidServerAddress"; }
  if (!shouldIntercept(item, origin, settings)) return "unsupported";

  let paused = false;
  try {
    await api.downloads.pause(item.id);
    paused = true;
  } catch {
    if (!completionHeld) {
      await safeReport(report, "notPaused");
      return "notPaused";
    }
    // Filename determination still prevents completion while suggest is pending.
  }

  let taskId;
  let submitted = false;
  try {
    const url = downloadUrl(item);
    const cookies = await cookiesForDownload(api, url);
    const body = {
      url,
      ...(fileName(item) ? { fileName: fileName(item) } : {}),
      ...(cookies ? { cookies } : {}),
      ...(item.referrer ? { referer: item.referrer } : {}),
      ...(userAgent ? { userAgent } : {}),
    };
    submitted = true;
    taskId = await submitDownload(fetcher, origin, settings.token, body);

    const current = (await api.downloads.search({ id: item.id }))[0];
    if (current?.state === "complete") throw new Error("chromeCompleted");
    await api.downloads.cancel(item.id);
    try { await api.downloads.erase({ id: item.id }); } catch { /* Cosmetic only. */ }
    await safeReport(report, "transferred");
    return "transferred";
  } catch (error) {
    let duplicateRisk = submitted && !taskId && !/^http\d+$/.test(error?.message ?? "");
    if (taskId) {
      try { await rollback(fetcher, origin, settings.token, taskId); }
      catch { duplicateRisk = true; }
    }
    if (paused) {
      try { await api.downloads.resume(item.id); } catch { /* It may already be complete. */ }
    }
    const outcome = duplicateRisk ? "duplicateRisk" : "fallback";
    await safeReport(report, outcome, error instanceof Error ? error.message : String(error));
    return outcome;
  }
}

/** Explicit right-click action; independent of the automatic capture switch. */
export async function submitLink(info, { api, fetcher = fetch, userAgent = "", report = () => {} }) {
  const stored = await api.storage.sync.get({ settings: DEFAULT_SETTINGS });
  const settings = normalizeSettings(stored.settings);
  if (!settings.host) {
    await safeReport(report, "notConfigured");
    return "notConfigured";
  }
  let origin;
  try { origin = serverOrigin(settings); }
  catch {
    await safeReport(report, "invalidServerAddress");
    return "invalidServerAddress";
  }
  const url = downloadUrl({ url: info.linkUrl });
  if (!url || new URL(url).origin === origin) {
    await safeReport(report, "unsupportedLink");
    return "unsupportedLink";
  }
  let submitted = false;
  try {
    const cookies = await cookiesForDownload(api, url);
    const referer = downloadUrl({ url: info.frameUrl || info.pageUrl });
    const body = {
      url,
      ...(cookies ? { cookies } : {}),
      ...(referer ? { referer } : {}),
      ...(userAgent ? { userAgent } : {}),
    };
    submitted = true;
    await submitDownload(fetcher, origin, settings.token, body);
    await safeReport(report, "linkTransferred");
    return "linkTransferred";
  } catch (error) {
    const code = submitted && !/^http\d+$/.test(error?.message ?? "") ? "duplicateRisk" : "linkFailed";
    await safeReport(report, code, error instanceof Error ? error.message : String(error));
    return code;
  }
}
