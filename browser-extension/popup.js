import { serverOrigin } from "./core.js";
import { loadSettings, localize, message } from "./ui.js";

localize();
const enabled = document.getElementById("enabled");
const server = document.getElementById("server");
const status = document.getElementById("status");
const detail = document.getElementById("detail");
const openServer = document.getElementById("openServer");
let settings = await loadSettings();

enabled.checked = settings.enabled;
let origin;
try { if (settings.host) origin = serverOrigin(settings); } catch { /* Show settings instead. */ }
server.textContent = origin || message(settings.host ? "invalidServerAddress" : "notConfigured");
openServer.disabled = !origin;
const { lastResult } = await chrome.storage.local.get("lastResult");
status.textContent = lastResult?.code ? message(lastResult.code) : message("ready");
if (lastResult?.detail && lastResult.code !== "transferred") {
  status.title = lastResult.detail;
  detail.textContent = `${message("failureDetail")}: ${lastResult.detail}`;
}

enabled.addEventListener("change", async () => {
  settings = { ...settings, enabled: enabled.checked };
  await chrome.storage.sync.set({ settings });
  status.textContent = message(enabled.checked ? "enabled" : "disabled");
  detail.textContent = "";
});
document.getElementById("options").addEventListener("click", () => chrome.runtime.openOptionsPage());
openServer.addEventListener("click", () => chrome.tabs.create({ url: origin }));
