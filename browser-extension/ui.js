import { DEFAULT_SETTINGS, normalizeSettings } from "./core.js";

export function message(key, substitutions) {
  return chrome.i18n.getMessage(key, substitutions) || key;
}

export function localize(root = document) {
  root.documentElement.lang = chrome.i18n.getUILanguage();
  for (const node of root.querySelectorAll("[data-i18n]")) {
    node.textContent = message(node.dataset.i18n);
  }
  for (const node of root.querySelectorAll("[data-i18n-placeholder]")) {
    node.placeholder = message(node.dataset.i18nPlaceholder);
  }
}

export async function loadSettings() {
  const stored = await chrome.storage.sync.get({ settings: DEFAULT_SETTINGS });
  return normalizeSettings(stored.settings);
}
