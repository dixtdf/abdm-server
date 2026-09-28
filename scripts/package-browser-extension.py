#!/usr/bin/env python3
"""Build a Chrome extension ZIP and optionally a signed CRX3."""

import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXTENSION = ROOT / "browser-extension"
RUNTIME_FILES = (
    "manifest.json",
    "background.js",
    "core.js",
    "interceptor.js",
    "popup.html",
    "popup.js",
    "options.html",
    "options.js",
    "ui.js",
    "style.css",
    "_locales/en/messages.json",
    "_locales/zh_CN/messages.json",
)


def chrome_binary() -> str:
    candidates = [os.environ.get("CHROME_BIN")]
    candidates.extend(shutil.which(name) for name in ("google-chrome", "chrome", "chromium"))
    if os.name == "nt":
        candidates.extend(
            str(Path(base) / "Google/Chrome/Application/chrome.exe")
            for base in (os.environ.get("PROGRAMFILES"), os.environ.get("PROGRAMFILES(X86)"))
            if base
        )
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate).resolve())
    raise SystemExit("Chrome is required to sign a CRX; set CHROME_BIN to chrome.exe/google-chrome")


def openssl_binary() -> str:
    candidate = shutil.which("openssl")
    if not candidate and os.name == "nt":
        candidate = r"C:\Program Files\Git\usr\bin\openssl.exe"
    if not candidate or not Path(candidate).is_file():
        raise SystemExit("OpenSSL is required to check the CRX signing key")
    return candidate


def sign_crx(archive_path: Path, key_path: Path, manifest: dict) -> Path:
    if not key_path.is_file():
        raise SystemExit(f"CRX signing key not found: {key_path}")
    try:
        public = subprocess.run(
            [openssl_binary(), "pkey", "-in", str(key_path), "-pubout", "-outform", "DER"],
            capture_output=True,
            check=True,
        ).stdout
    except subprocess.CalledProcessError:
        raise SystemExit("CRX signing key is not a readable PEM private key") from None
    if public != base64.b64decode(manifest["key"], validate=True):
        raise SystemExit("CRX signing key does not match manifest.key; refusing to change the extension ID")

    with tempfile.TemporaryDirectory(prefix="abdm-crx-") as temp:
        root = Path(temp)
        extension_dir = root / "extension"
        extension_dir.mkdir()
        with zipfile.ZipFile(archive_path) as archive:
            archive.extractall(extension_dir)

        command = [
            chrome_binary(),
            f"--pack-extension={extension_dir}",
            f"--pack-extension-key={key_path}",
            f"--user-data-dir={root / 'chrome-profile'}",
            "--no-message-box",
            "--no-first-run",
            "--no-default-browser-check",
        ]
        if sys.platform.startswith("linux") and not os.environ.get("DISPLAY"):
            xvfb = shutil.which("xvfb-run")
            command = [xvfb, "-a", *command] if xvfb else [*command, "--headless=new"]
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
        packed = extension_dir.with_suffix(".crx")
        if result.returncode != 0 or not packed.is_file():
            raise SystemExit(f"Chrome failed to pack CRX: {result.stderr[-1200:]}")
        header = packed.read_bytes()[:8]
        if header != b"Cr24\x03\x00\x00\x00":
            raise SystemExit("Chrome did not produce a CRX3 package")
        target = archive_path.with_suffix(".crx")
        shutil.copyfile(packed, target)
        print(f"Chrome extension CRX3: {target}")
        return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--crx-key", type=Path, help="PEM private key matching manifest.key")
    args = parser.parse_args()
    version, output_dir = args.version, args.output_dir
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", version):
        raise SystemExit(f"invalid release version: {version}")

    manifest = json.loads((EXTENSION / "manifest.json").read_text(encoding="utf-8"))
    # Chrome's manifest version cannot contain a prerelease suffix.
    expected = version.split("-", 1)[0]
    if manifest["version"] != expected:
        raise SystemExit(
            f"extension version {manifest['version']} does not match release {version}; "
            "run scripts/set-version.ps1 first"
        )
    if version != expected:
        manifest["version_name"] = version

    missing = [name for name in RUNTIME_FILES if not (EXTENSION / name).is_file()]
    if missing:
        raise SystemExit(f"missing extension runtime file(s): {', '.join(missing)}")

    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / f"abdm-server-chrome-extension-{version}.zip"
    with zipfile.ZipFile(target, "w") as archive:
        for name in RUNTIME_FILES:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            payload = (
                (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
                if name == "manifest.json" and version != expected
                else (EXTENSION / name).read_bytes()
            )
            archive.writestr(info, payload)
    print(f"Chrome extension: {target} ({len(RUNTIME_FILES)} files)")
    if args.crx_key:
        sign_crx(target, args.crx_key.resolve(), manifest)


if __name__ == "__main__":
    main()
