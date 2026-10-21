#!/usr/bin/env python3
"""Small ADB helper for the TV.

  tools/tv.py 'shell command'            run a shell command on the TV
  tools/tv.py --key DPAD_DOWN [...]      inject remote key presses
  tools/tv.py --screenshot shots/x.png   capture the screen and pull the PNG

The TV address comes from TV_HOST / TV_PORT, or from "tv_host" / "tv_port"
in local.json at the repo root (see local.example.json).
"""
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def local_config():
    f = ROOT / "local.json"
    if f.exists():
        return json.loads(f.read_text(encoding="utf-8"))
    return {}


def find_adb():
    bundled = ROOT / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    if bundled.exists():
        return str(bundled)
    return shutil.which("adb") or "adb"


ADB = find_adb()
_cfg = local_config()
HOST = os.environ.get("TV_HOST") or _cfg.get("tv_host", "")
PORT = os.environ.get("TV_PORT") or str(_cfg.get("tv_port", 5555))
SERIAL = f"{HOST}:{PORT}"


def adb(*args, check=False):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, check=check)


def ensure_connected():
    if not HOST:
        sys.exit("No TV address: set TV_HOST or put \"tv_host\" in local.json (copy local.example.json).")
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    if f"{SERIAL}\tdevice" not in out:
        subprocess.run([ADB, "connect", SERIAL], capture_output=True, text=True)


def shell(cmd):
    r = adb("shell", cmd)
    return r.stdout + r.stderr


def screenshot(dest):
    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shell("screencap -p /sdcard/tv_shot.png")
    adb("pull", "/sdcard/tv_shot.png", str(dest))
    shell("rm /sdcard/tv_shot.png")
    if not dest.exists() or dest.stat().st_size == 0:
        print("screenshot is empty: Live TV / HDMI / DRM content cannot be captured; "
              "check `dumpsys activity activities | grep mResumedActivity` instead")
        sys.exit(1)
    print(dest)


def main():
    ensure_connected()
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return
    if args[0] == "--screenshot":
        screenshot(args[1] if len(args) > 1 else "shots/shot.png")
    elif args[0] == "--key":
        for k in args[1:]:
            shell(f"input keyevent KEYCODE_{k}" if not k.isdigit() else f"input keyevent {k}")
    else:
        sys.stdout.write(shell(" ".join(args)))


if __name__ == "__main__":
    main()
