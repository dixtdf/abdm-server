import test from "node:test";
import assert from "node:assert/strict";
import { PRESET_EXTENSIONS, cookieHeader, parseExtensions, serverOrigin, shouldIntercept } from "../core.js";
import { interceptDownload, submitLink } from "../interceptor.js";

function fixture({ settings = {}, responseStatus = 201, cancelFails = false, pauseFails = false, fetchFails = false } = {}) {
  const calls = [];
  const api = {
    storage: { sync: { get: async () => ({ settings: { enabled: true, protocol: "http", host: "10.10.10.2", port: 6868, token: "secret", ...settings } }) } },
    cookies: {
      getAll: async (details) => { calls.push(["cookies", details]); return [{ name: "session", value: "login", httpOnly: true }]; },
    },
    downloads: {
      pause: async (id) => { calls.push(["pause", id]); if (pauseFails) throw Error("finished"); },
      search: async () => [{ state: "in_progress" }],
      cancel: async (id) => { calls.push(["cancel", id]); if (cancelFails) throw Error("cancel failed"); },
      erase: async (query) => { calls.push(["erase", query]); },
      resume: async (id) => { calls.push(["resume", id]); },
    },
  };
  const fetcher = async (url, init) => {
    calls.push(["fetch", url, init]);
    if (fetchFails) throw Error("connection lost");
    return { status: init.method === "DELETE" ? 204 : responseStatus, json: async () => ({ id: "task-1" }) };
  };
  const item = { id: 7, url: "https://private.example/file", filename: "C:\\Downloads\\report.zip", referrer: "https://private.example/account" };
  return { api, calls, fetcher, item };
}

test("authenticated download sends cookies and token, then cancels Chrome", async () => {
  const { api, calls, fetcher, item } = fixture();
  assert.equal(await interceptDownload(item, { api, fetcher, userAgent: "Chrome test" }), "transferred");
  assert.deepEqual(calls.map(([name]) => name), ["pause", "cookies", "fetch", "cancel", "erase"]);
  const post = calls.find(([name]) => name === "fetch")[2];
  assert.equal(post.headers.Authorization, "Bearer secret");
  assert.deepEqual(JSON.parse(post.body), {
    url: "https://private.example/file",
    fileName: "report.zip",
    cookies: "session=login",
    referer: "https://private.example/account",
    userAgent: "Chrome test",
  });
  assert.deepEqual(calls[1][1], { url: "https://private.example/file" });
});

test("server rejection resumes Chrome without cancelling", async () => {
  const { api, calls, fetcher, item } = fixture({ responseStatus: 401 });
  assert.equal(await interceptDownload(item, { api, fetcher }), "fallback");
  assert.deepEqual(calls.map(([name]) => name), ["pause", "cookies", "fetch", "resume"]);
});

test("cancel failure rolls server task back and resumes Chrome", async () => {
  const { api, calls, fetcher, item } = fixture({ cancelFails: true });
  assert.equal(await interceptDownload(item, { api, fetcher }), "fallback");
  assert.deepEqual(calls.map(([name]) => name), ["pause", "cookies", "fetch", "cancel", "fetch", "resume"]);
  assert.match(calls[4][1], /task-1\?deleteFile=true$/);
});

test("non-pausable downloads stay in Chrome", async () => {
  const { api, calls, fetcher, item } = fixture({ pauseFails: true });
  assert.equal(await interceptDownload(item, { api, fetcher }), "notPaused");
  assert.deepEqual(calls.map(([name]) => name), ["pause"]);
});

test("held filename determination can hand off a small download even if pause fails", async () => {
  const { api, calls, fetcher, item } = fixture({ pauseFails: true });
  assert.equal(await interceptDownload(item, { api, fetcher, completionHeld: true }), "transferred");
  assert.deepEqual(calls.map(([name]) => name), ["pause", "cookies", "fetch", "cancel", "erase"]);
});

test("right-click sends a link with cookies even when automatic capture is off", async () => {
  const { api, calls, fetcher } = fixture({ settings: { enabled: false } });
  const result = await submitLink({
    linkUrl: "https://private.example/file",
    pageUrl: "https://private.example/account",
  }, { api, fetcher, userAgent: "Chrome test" });
  assert.equal(result, "linkTransferred");
  assert.deepEqual(calls.map(([name]) => name), ["cookies", "fetch"]);
  assert.deepEqual(JSON.parse(calls[1][2].body), {
    url: "https://private.example/file",
    cookies: "session=login",
    referer: "https://private.example/account",
    userAgent: "Chrome test",
  });
});

test("uncertain network outcome warns about duplicates while resuming Chrome", async () => {
  const { api, calls, fetcher, item } = fixture({ fetchFails: true });
  assert.equal(await interceptDownload(item, { api, fetcher }), "duplicateRisk");
  assert.deepEqual(calls.map(([name]) => name), ["pause", "cookies", "fetch", "resume"]);
});

test("non-http and server downloads are excluded", () => {
  const origin = serverOrigin({ host: "10.10.10.2", port: 6868 });
  assert.equal(shouldIntercept({ url: "blob:https://example.com/id" }, origin), false);
  assert.equal(shouldIntercept({ url: `${origin}/export` }, origin), false);
  assert.equal(shouldIntercept({ url: "https://example.com/file" }, origin), true);
  assert.equal(shouldIntercept({ url: "https://example.com/file", state: "interrupted" }, origin), false);
});

test("extension filter is optional and presets cover common file types", () => {
  assert.ok(PRESET_EXTENSIONS.length >= 100);
  for (const extension of ["sig", "iso", "zip", "7z", "exe", "mp4", "pdf", "apk", "torrent"]) {
    assert.ok(PRESET_EXTENSIONS.includes(extension));
  }
  const origin = serverOrigin({ host: "10.10.10.2", port: 6868 });
  const settings = { captureMode: "extensions", extensions: "sig, iso, tar.gz" };
  assert.equal(shouldIntercept({ url: "https://example.com/a.sig" }, origin, settings), true);
  assert.equal(shouldIntercept({ url: "https://example.com/a", filename: "C:\\Downloads\\a.iso" }, origin, settings), true);
  assert.equal(shouldIntercept({ url: "https://example.com/a.tar.gz" }, origin, settings), true);
  assert.equal(shouldIntercept({ url: "https://example.com/a.html" }, origin, settings), false);
  assert.deepEqual(parseExtensions("*.ISO, .sig; SIG"), ["iso", "sig"]);
});

test("cookie formatting rejects header injection", () => {
  assert.equal(cookieHeader([{ name: "a", value: "b" }, { name: "bad\nHeader", value: "x" }]), "a=b");
});
