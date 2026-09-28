import { normalizeSettings, serverOrigin } from "./core.js";
import { loadSettings, localize, message } from "./ui.js";

localize();
const form = document.getElementById("settingsForm");
const status = document.getElementById("status");
const fields = Object.fromEntries(["enabled", "protocol", "host", "port", "token", "captureMode", "extensions"].map((key) => [key, document.getElementById(key)]));
const saved = await loadSettings();
fields.enabled.checked = saved.enabled;
for (const key of ["protocol", "host", "port", "token", "captureMode", "extensions"]) fields[key].value = saved[key];
document.getElementById("extensionId").textContent = chrome.runtime.id;

function readForm() {
  const settings = normalizeSettings({
    enabled: fields.enabled.checked,
    protocol: fields.protocol.value,
    host: fields.host.value,
    port: fields.port.value,
    token: fields.token.value,
    captureMode: fields.captureMode.value,
    extensions: fields.extensions.value,
  });
  serverOrigin(settings);
  return settings;
}

function showError(error) {
  status.textContent = error.message === "invalidServerAddress" ? message("invalidServerAddress") : String(error);
  status.className = "error";
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    const settings = readForm();
    await chrome.storage.sync.set({ settings });
    status.textContent = message("saved");
    status.className = "success";
  } catch (error) { showError(error); }
});

const probeStatus = document.getElementById("probeStatus");
async function showProbe() {
  const { syncProbe } = await chrome.storage.sync.get("syncProbe");
  probeStatus.textContent = syncProbe?.code
    ? `${message("probeCode")}: ${syncProbe.code} (${new Date(syncProbe.at).toLocaleString()})`
    : message("probeMissing");
}
document.getElementById("writeProbe").addEventListener("click", async () => {
  try {
    const code = Array.from(crypto.getRandomValues(new Uint8Array(4)), (byte) => byte.toString(16).padStart(2, "0")).join("").toUpperCase();
    await chrome.storage.sync.set({ syncProbe: { code, at: Date.now() } });
    await showProbe();
  } catch (error) {
    probeStatus.textContent = `${message("probeWriteFailed")}: ${error.message}`;
  }
});
document.getElementById("readProbe").addEventListener("click", showProbe);
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "sync" && changes.syncProbe) void showProbe();
});
await showProbe();

document.getElementById("test").addEventListener("click", async () => {
  try {
    const settings = readForm();
    const response = await fetch(`${serverOrigin(settings)}/api/v1/version`, {
      headers: settings.token ? { Authorization: `Bearer ${settings.token}` } : {},
      signal: AbortSignal.timeout(10000),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const server = await response.json();
    if (!server.version || !server.engine) throw new Error(message("invalidServerResponse"));
    const isAbdm = server.engine === "abdm";
    status.textContent = isAbdm
      ? `${message("connectionOk")} ${server.version} / ${server.engine}`
      : `${message("serverNeedsUpdate")} ${server.version} / ${server.engine}`;
    status.className = isAbdm ? "success" : "error";
  } catch (error) {
    status.textContent = `${message("connectionFailed")}: ${error.message}`;
    status.className = "error";
  }
});
