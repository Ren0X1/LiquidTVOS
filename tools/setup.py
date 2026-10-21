#!/usr/bin/env python3
"""Download what this repo needs into the repo folder (Windows, macOS, Linux).

  tools/setup.py

- platform-tools/  adb, from Google
- android-sdk/     command-line tools + platforms;android-35 + build-tools;35.0.0
                   (skipped when ANDROID_HOME already points at an SDK)

A JDK 17+ must be installed separately (java and keytool on PATH or JAVA_HOME).
"""
import os
import platform
import shutil
import stat
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OS = {"Windows": "windows", "Darwin": "darwin", "Linux": "linux"}[platform.system()]
CMDLINE = {"windows": "win", "darwin": "mac", "linux": "linux"}[OS]
BASE = "https://dl.google.com/android/repository/"
PLATFORM_TOOLS = f"{BASE}platform-tools-latest-{OS}.zip"
CMDLINE_TOOLS = f"{BASE}commandlinetools-{CMDLINE}-13114758_latest.zip"
PACKAGES = ["platforms;android-35", "build-tools;35.0.0"]


def fetch_zip(url, dest):
    tmp = ROOT / "download.zip"
    print("downloading", url)
    urllib.request.urlretrieve(url, tmp)
    with zipfile.ZipFile(tmp) as z:
        z.extractall(dest)
        # zipfile drops the executable bit
        for info in z.infolist():
            mode = info.external_attr >> 16
            if mode & stat.S_IXUSR:
                p = Path(dest) / info.filename
                p.chmod(p.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    tmp.unlink()


def main():
    if not (ROOT / "platform-tools").exists():
        fetch_zip(PLATFORM_TOOLS, ROOT)
    else:
        print("platform-tools: already there")

    if os.environ.get("ANDROID_HOME") and Path(os.environ["ANDROID_HOME"]).is_dir():
        print("android sdk: using ANDROID_HOME =", os.environ["ANDROID_HOME"])
    else:
        sdk = ROOT / "android-sdk"
        latest = sdk / "cmdline-tools" / "latest"
        if not latest.exists():
            fetch_zip(CMDLINE_TOOLS, sdk / "cmdline-tools")
            shutil.move(str(sdk / "cmdline-tools" / "cmdline-tools"), str(latest))
        manager = latest / "bin" / ("sdkmanager.bat" if OS == "windows" else "sdkmanager")
        print("installing", ", ".join(PACKAGES), "(accepting the SDK licences)")
        subprocess.run([str(manager), f"--sdk_root={sdk}", *PACKAGES],
                       input="y\n" * 20, text=True, check=True, shell=(OS == "windows"))

    if not shutil.which("java") and not os.environ.get("JAVA_HOME"):
        print("\n! No JDK found. Install a JDK 17+ (for example Temurin) before building.")
    print("\nDone. Next: copy local.example.json to local.json and set your TV's IP.")


if __name__ == "__main__":
    sys.exit(main())
