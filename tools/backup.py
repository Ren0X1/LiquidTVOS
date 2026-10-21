#!/usr/bin/env python3
"""Save the TV's state before changing anything.

  tools/backup.py        writes backup/<date-time>/

Package lists (all, disabled, system, third party), getprop, every settings
namespace, display size and density, the apps that answer Home, and a
screenshot of the stock home screen.
"""
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

FILES = {
    "packages-all.txt": "pm list packages -f",
    "packages-disabled.txt": "pm list packages -d",
    "packages-system.txt": "pm list packages -s",
    "packages-thirdparty.txt": "pm list packages -3",
    "getprop.txt": "getprop",
    "settings-system.txt": "settings list system",
    "settings-secure.txt": "settings list secure",
    "settings-global.txt": "settings list global",
    "display.txt": "wm size; wm density; settings get secure display_density_forced",
    "home-activities.txt": "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME",
}


def main():
    tv.ensure_connected()
    out = tv.ROOT / "backup" / datetime.now().strftime("%Y%m%d-%H%M")
    out.mkdir(parents=True, exist_ok=True)
    for name, cmd in FILES.items():
        # one command per call: chained shell commands can stop silently on some firmware
        parts = [c.strip() for c in cmd.split(";")]
        (out / name).write_text("\n".join(tv.shell(c) for c in parts), encoding="utf-8")
    try:
        tv.screenshot(out / "00-stock-home.png")
    except SystemExit:
        pass
    print(f"backup saved to {out}")


if __name__ == "__main__":
    main()
