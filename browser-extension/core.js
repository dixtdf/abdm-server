export const PRESET_EXTENSIONS = Object.freeze([
  "7z", "apk", "appimage", "arj", "asar", "bin", "bz2", "cab", "crx", "deb", "dmg", "exe", "gz", "iso", "jar", "msi", "msix", "pkg", "rar", "rpm", "tar", "tar.bz2", "tar.gz", "tar.xz", "tgz", "xz", "zip", "zst",
  "aac", "aiff", "alac", "ape", "flac", "m4a", "mid", "mp3", "ogg", "opus", "wav", "wma",
  "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "ts", "vob", "webm", "wmv",
  "avif", "bmp", "gif", "heic", "heif", "ico", "jpeg", "jpg", "png", "psd", "raw", "svg", "tif", "tiff", "webp",
  "azw", "azw3", "cbz", "djvu", "doc", "docx", "epub", "mobi", "odt", "pdf", "ppt", "pptx", "rtf", "txt", "xls", "xlsx",
  "csv", "db", "json", "parquet", "sqlite", "sql", "xml", "yaml", "yml",
  "img", "qcow2", "sig", "torrent", "vhd", "vhdx", "vmdk", "wasm", "whl",
]);

export const DEFAULT_SETTINGS = Object.freeze({
  enabled: true,
  protocol: "http",
  host: "",
  port: 6868,
  token: "",
  captureMode: "all",
  extensions: PRESET_EXTENSIONS.join(", "),
});

export function parseExtensions(value) {
  const values = Array.isArray(value) ? value : String(value ?? "").split(/[\s,;，；]+/);
  return [...new Set(values.map((item) => String(item).trim().toLowerCase().replace(/^\*?\.+/, "")).filter((item) => /^[a-z0-9]+(?:\.[a-z0-9]+)*$/.test(item)))];
}

export function normalizeSettings(value = {}) {
  const settings = { ...DEFAULT_SETTINGS, ...value };
  settings.enabled = settings.enabled !== false;
  settings.protocol = settings.protocol === "https" ? "https" : "http";
  settings.host = String(settings.host ?? "").trim();
  settings.port = Number(settings.port);
  settings.token = String(settings.token ?? "").trim();
  settings.captureMode = settings.captureMode === "extensions" ? "extensions" : "all";
  settings.extensions = parseExtensions(settings.extensions).join(", ");
  return settings;
}

export function serverOrigin(settings) {
  const { protocol, host, port } = normalizeSettings(settings);
  if (!host || /[\s/?#@]/.test(host) || !Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("invalidServerAddress");
  }
  const hostname = host.startsWith("[") && host.endsWith("]")
    ? host
    : host.includes(":") ? `[${host}]` : host;
  const url = new URL(`${protocol}://${hostname}:${port}/`);
  if (url.hostname === "" || url.username || url.password) throw new Error("invalidServerAddress");
  return url.origin;
}

export function downloadUrl(item) {
  for (const candidate of [item.finalUrl, item.url]) {
    try {
      const url = new URL(candidate);
      if (url.protocol === "http:" || url.protocol === "https:") return url.href;
    } catch { /* Try the original URL. */ }
  }
  return null;
}

export function shouldIntercept(item, origin, settings = DEFAULT_SETTINGS) {
  if (item.byExtensionId || (item.state && item.state !== "in_progress")) return false;
  const url = downloadUrl(item);
  if (url === null || new URL(url).origin === origin) return false;
  if (settings.captureMode !== "extensions") return true;
  const rawName = new URL(url).pathname.split("/").pop() || "";
  let urlName = rawName;
  try { urlName = decodeURIComponent(rawName); } catch { /* Keep raw URL path. */ }
  const names = [fileName(item), urlName].filter(Boolean).map((name) => name.toLowerCase());
  return parseExtensions(settings.extensions).some((extension) => names.some((name) => name.endsWith(`.${extension}`)));
}

export function fileName(item) {
  const name = String(item.filename ?? "").split(/[\\/]/).pop();
  return name || undefined;
}

export function cookieHeader(cookies) {
  return cookies
    .filter((cookie) => cookie.name && !/[;\r\n]/.test(cookie.name) && !/[\r\n]/.test(cookie.value))
    .map((cookie) => `${cookie.name}=${cookie.value}`)
    .join("; ");
}
