#!/usr/bin/env python3
"""Build a Chrome-loadable ZIP from the extension's runtime files only."""

import json
import re
import sys
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


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: package-browser-extension.py VERSION OUTPUT_DIR")
    version, output_dir = sys.argv[1], Path(sys.argv[2])
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


if __name__ == "__main__":
    main()
