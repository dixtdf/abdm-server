import { interceptDownload, submitLink } from "./interceptor.js";

const LINK_MENU_ID = "send-link-to-abdm";
const handoffs = new Map();
const settledHandoffs = new Map();

async function report(code, detail) {
  await chrome.storage.local.set({ lastResult: { code, detail, at: Date.now() } });
  await chrome.action.setBadgeText({ text: ["transferred", "linkTransferred"].includes(code) ? "OK" : "!" });
  await chrome.action.setBadgeBackgroundColor({ color: ["transferred", "linkTransferred"].includes(code) ? "#1d985f" : "#c84c4c" });
}

async function reportUnexpected(error) {
  console.error("ABDM download capture failed", error);
  try { await report("unexpectedError", String(error)); } catch { /* Nothing else to do. */ }
}

chrome.runtime.onInstalled.addListener(() => {
  // Content scripts are not used; keep synced settings out of them if added later.
  void chrome.storage.sync.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
  chrome.contextMenus.create({
    id: LINK_MENU_ID,
    title: chrome.i18n.getMessage("sendLinkToAbdm"),
    contexts: ["link"],
    targetUrlPatterns: ["http://*/*", "https://*/*"],
  });
});

function handoff(item, completionHeld = false) {
  if (settledHandoffs.has(item.id)) return Promise.resolve(settledHandoffs.get(item.id));
  const existing = handoffs.get(item.id);
  if (existing) return existing;
  const transfer = interceptDownload(item, {
    api: chrome,
    userAgent: navigator.userAgent,
    report,
    completionHeld,
  }).catch(reportUnexpected).then((outcome) => {
    if (outcome && !["notPaused", "unsupported"].includes(outcome)) {
      settledHandoffs.set(item.id, outcome);
      if (settledHandoffs.size > 512) settledHandoffs.delete(settledHandoffs.keys().next().value);
    }
    return outcome;
  }).finally(() => handoffs.delete(item.id));
  handoffs.set(item.id, transfer);
  return transfer;
}

chrome.downloads.onDeterminingFilename.addListener((item, suggest) => {
  void handoff(item, true).finally(() => {
    try { suggest(); } catch (error) { console.error("ABDM filename release failed", error); }
  });
  return true;
});

chrome.downloads.onCreated.addListener((item) => {
  void handoff(item);
});

chrome.contextMenus.onClicked.addListener((info) => {
  if (info.menuItemId !== LINK_MENU_ID) return;
  void submitLink(info, {
    api: chrome,
    userAgent: navigator.userAgent,
    report,
  }).catch(reportUnexpected);
});
