import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const read = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), "utf8"));

test("all extension UI messages exist in both locales", () => {
  const en = read("../_locales/en/messages.json");
  const zh = read("../_locales/zh_CN/messages.json");
  assert.deepEqual(Object.keys(en).sort(), Object.keys(zh).sort());
  for (const path of ["../popup.html", "../options.html"]) {
    const html = readFileSync(new URL(path, import.meta.url), "utf8");
    for (const match of html.matchAll(/data-i18n(?:-placeholder)?="([^"]+)"/g)) {
      assert.ok(en[match[1]], `missing locale key: ${match[1]}`);
    }
  }
});

test("manifest version and localized metadata are valid", () => {
  const manifest = read("../manifest.json");
  assert.equal(manifest.manifest_version, 3);
  assert.equal(manifest.default_locale, "en");
  assert.ok(manifest.permissions.includes("contextMenus"));
  assert.match(manifest.version, /^\d+\.\d+\.\d+$/);
  assert.ok(read("../_locales/en/messages.json").extensionName);
  assert.ok(read("../_locales/en/messages.json").sendLinkToAbdm);
  const digest = createHash("sha256").update(Buffer.from(manifest.key, "base64")).digest();
  const id = [...digest.subarray(0, 16)].map((byte) => String.fromCharCode(97 + (byte >> 4), 97 + (byte & 15))).join("");
  assert.equal(id, "pjmoncdpljccobiiaeckifkejellfaph");
});
