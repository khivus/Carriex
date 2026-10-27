#!/usr/bin/env python3
"""Export paired screenshots produced by LocalizationAndroidTest (debug app only)."""
import argparse
import html
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", help="Path to adb if it is not on PATH")
    parser.add_argument("--serial", help="Required if multiple Android devices are connected")
    parser.add_argument("--output", type=Path, default=ROOT / "build/localization-screenshots")
    args = parser.parse_args()
    adb = args.adb or shutil.which("adb")
    if not adb:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        properties = ROOT / "local.properties"
        if not sdk and properties.exists():
            match = re.search(r"^sdk.dir=(.+)$", properties.read_text(encoding="utf-8"), re.M)
            if match:
                sdk = match[1].replace("\\:", ":").replace("\\\\", "\\").strip()
        if sdk:
            candidate = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if candidate.exists():
                adb = str(candidate)
    if not adb:
        parser.error("Cannot find adb. Supply --adb or set ANDROID_HOME.")
    prefix = [adb] + (["-s", args.serial] if args.serial else [])
    result = subprocess.run(prefix + ["shell", "run-as", "com.example.carriex", "ls", "files/localization-review"], capture_output=True, check=True)
    names = result.stdout.decode("utf-8").split()
    args.output.mkdir(parents=True, exist_ok=True)
    for name in names:
        if not re.fullmatch(r"(en|ru)-(settings|appearance|privacy|chats|notifications)\.png", name):
            continue
        data = subprocess.run(prefix + ["exec-out", "run-as", "com.example.carriex", "cat", "files/localization-review/" + name], capture_output=True, check=True).stdout
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError(f"Invalid screenshot: {name}")
        (args.output / name).write_bytes(data)
    sections = []
    for section in ("settings", "chats", "privacy", "appearance", "notifications"):
        if all((args.output / f"{tag}-{section}.png").exists() for tag in ("en", "ru")):
            sections.append(f'<h2>{html.escape(section)}</h2><div class="pair"><figure><figcaption>English</figcaption><img src="en-{section}.png" alt="English {section}"></figure><figure><figcaption>Русский</figcaption><img src="ru-{section}.png" alt="Русский: {section}"></figure></div>')
    if not sections:
        raise RuntimeError("No paired screenshots. Run LocalizationAndroidTest first.")
    (args.output / "index.html").write_text('<!doctype html><html lang="en"><meta charset="utf-8"><title>Carriex · EN/RU screenshots</title><style>body{font:16px system-ui;max-width:1100px;margin:24px auto;padding:0 12px}.pair{display:flex;gap:20px}figure{margin:0;width:50%}img{width:100%;height:auto}figcaption{padding:10px}</style><h1>English / Русский</h1>' + "".join(sections) + '</html>', encoding="utf-8")
    print((args.output / "index.html").resolve())


if __name__ == "__main__":
    main()
