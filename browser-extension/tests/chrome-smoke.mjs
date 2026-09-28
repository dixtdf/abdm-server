// Optional real-Chrome smoke test: node tests/chrome-smoke.mjs
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const extension = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const profile = await mkdtemp(path.join(extension, ".chrome-smoke-"));
const chromeBin = process.env.CHROME_BIN || "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
function withTimeout(promise, ms, onTimeout) {
  let timer;
  const expired = new Promise((_, reject) => {
    timer = setTimeout(async () => {
      try { reject(await onTimeout()); } catch (error) { reject(error); }
    }, ms);
  });
  return Promise.race([promise, expired]).finally(() => clearTimeout(timer));
}
const listen = (server) => new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const close = (server) => new Promise((resolve) => server.close(resolve));
let received;
let resolveReceived;
const receivedPromise = new Promise((resolve) => { resolveReceived = resolve; });
const api = createServer(async (req, res) => {
  if (req.method === "POST" && req.url === "/api/v1/downloads") {
    let body = "";
    for await (const chunk of req) body += chunk;
    received = JSON.parse(body);
    resolveReceived(received);
    res.writeHead(201, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ id: "chrome-smoke-task" }));
  } else if (req.method === "DELETE") {
    res.writeHead(204).end();
  } else {
    res.writeHead(404).end();
  }
});
const source = createServer((_req, res) => {
  if (process.env.SMOKE_TINY === "1") {
    res.writeHead(200, { "Content-Type": "application/octet-stream", "Content-Disposition": "attachment; filename=\"test.sig\"" });
    res.end("tiny signature");
    return;
  }
  const total = 2 * 1024 * 1024;
  let sent = 0;
  res.writeHead(200, { "Content-Type": "application/octet-stream", "Content-Length": total, "Content-Disposition": "attachment; filename=\"test.sig\"" });
  const timer = setInterval(() => {
    if (sent >= total) { clearInterval(timer); res.end(); return; }
    const part = Buffer.alloc(Math.min(32 * 1024, total - sent), 42);
    sent += part.length;
    res.write(part);
  }, 25);
  res.on("close", () => clearInterval(timer));
});

function cdpOverPipe(child) {
  let nextId = 0;
  let buffer = Buffer.alloc(0);
  const pending = new Map();
  child.stdio[4].on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    let separator;
    while ((separator = buffer.indexOf(0)) >= 0) {
      const message = JSON.parse(buffer.subarray(0, separator).toString("utf8"));
      buffer = buffer.subarray(separator + 1);
      if (message.id && pending.has(message.id)) {
        const { resolve, reject } = pending.get(message.id);
        pending.delete(message.id);
        message.error ? reject(Error(`${message.error.message} (${message.id})`)) : resolve(message.result);
      }
    }
  });
  return (method, params = {}, sessionId) => new Promise((resolve, reject) => {
    const id = ++nextId;
    pending.set(id, { resolve, reject });
    child.stdio[3].write(`${JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) })}\0`);
  });
}

let chrome;
try {
  await Promise.all([listen(api), listen(source)]);
  chrome = spawn(chromeBin, [
    "--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-gpu",
    `--user-data-dir=${profile}`, "--remote-debugging-pipe", "--enable-unsafe-extension-debugging",
    "about:blank",
  ], { windowsHide: true, stdio: ["ignore", "ignore", "ignore", "pipe", "pipe"] });
  const send = cdpOverPipe(chrome);
  const { id } = await withTimeout(
    send("Extensions.loadUnpacked", { path: extension }),
    10000,
    () => Error("Chrome extension load timed out"),
  );
  await wait(1000);
  const { targetInfos } = await send("Target.getTargets");
  const worker = targetInfos.find((target) => target.url === `chrome-extension://${id}/background.js`);
  if (!worker) throw Error("Extension service worker did not start");
  const { sessionId } = await send("Target.attachToTarget", { targetId: worker.targetId, flatten: true });
  const settings = { enabled: true, protocol: "http", host: "127.0.0.1", port: api.address().port, token: "" };
  await send("Runtime.evaluate", {
    expression: `chrome.storage.sync.set({settings:${JSON.stringify(settings)}})`, awaitPromise: true,
  }, sessionId);
  await send("Browser.setDownloadBehavior", { behavior: "allow", downloadPath: profile });
  await send("Target.createTarget", { url: `http://127.0.0.1:${source.address().port}/test.sig` });
  await withTimeout(
    receivedPromise,
    10000,
    async () => {
      const state = await send("Runtime.evaluate", { expression: "Promise.all([chrome.storage.sync.get('settings'),chrome.storage.local.get('lastResult'),chrome.downloads.search({}),chrome.downloads.onDeterminingFilename.hasListeners(),chrome.runtime.lastError?.message])", awaitPromise: true, returnByValue: true }, sessionId);
      return Error(`No POST received from extension: ${JSON.stringify(state)}`);
    },
  );
  await wait(500);
  const state = await send("Runtime.evaluate", { expression: "chrome.storage.local.get('lastResult')", awaitPromise: true, returnByValue: true }, sessionId);
  const data = state.result?.value;
  const code = data?.lastResult?.code;
  if (code !== "transferred") throw Error(`Chrome handoff status: ${code || "missing"} (${data?.lastResult?.detail || ""})`);
  if (!received.url?.endsWith("/test.sig")) throw Error("Wrong download URL submitted");
  const { targetId } = await send("Target.createTarget", { url: `chrome-extension://${id}/options.html` });
  const { sessionId: optionsSession } = await send("Target.attachToTarget", { targetId, flatten: true });
  await wait(500);
  const options = await send("Runtime.evaluate", {
    expression: "({id:document.getElementById('extensionId')?.textContent, presets:document.getElementById('extensions')?.value, mode:document.getElementById('captureMode')?.value})",
    returnByValue: true,
  }, optionsSession);
  if (options.result?.value?.id !== id || !options.result.value.presets.includes("sig") || options.result.value.mode !== "all") {
    throw Error(`Options page did not load correctly: ${JSON.stringify(options)}`);
  }
  await send("Runtime.evaluate", { expression: "document.getElementById('writeProbe').click()" }, optionsSession);
  await wait(200);
  const probe = await send("Runtime.evaluate", { expression: "chrome.storage.sync.get('syncProbe')", awaitPromise: true, returnByValue: true }, optionsSession);
  if (!probe.result?.value?.syncProbe?.code) throw Error("Sync diagnostic test code was not saved");
  await send("Runtime.evaluate", {
    expression: "document.getElementById('captureMode').value='extensions';document.getElementById('extensions').value+=', customext';document.getElementById('settingsForm').requestSubmit()",
  }, optionsSession);
  await wait(200);
  const stored = await send("Runtime.evaluate", { expression: "chrome.storage.sync.get('settings')", awaitPromise: true, returnByValue: true }, optionsSession);
  if (stored.result?.value?.settings?.captureMode !== "extensions" || !stored.result.value.settings.extensions.includes("customext")) {
    throw Error("Extension filter was not saved to sync storage");
  }
  console.log(`Chrome handoff passed: ${received.url}`);
  console.log(`Options, extension filter and sync probe passed: ${id}`);
} finally {
  chrome?.kill();
  await Promise.all([close(api), close(source)]);
  const resolved = path.resolve(profile);
  if (!resolved.startsWith(`${extension}${path.sep}.chrome-smoke-`)) throw Error("Unexpected profile path; refusing cleanup");
  await rm(resolved, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
}
