#!/usr/bin/env python3
"""Re-enable what tools/debloat.py disabled.

  tools/restore.py              re-enable everything in debloat/disabled.txt
  tools/restore.py pkg1 ...     re-enable only these packages

Also puts the stock launcher back as Home if it was disabled.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

LOG = tv.ROOT / "debloat" / "disabled.txt"


def main():
    tv.ensure_connected()
    pkgs = sys.argv[1:] or (LOG.read_text().split() if LOG.exists() else [])
    for pkg in pkgs:
        out = tv.shell(f"pm enable --user 0 {pkg}").strip()
        print(f"{pkg}  {out}")
    if not sys.argv[1:]:
        if "com.google.android.tvlauncher" in pkgs:
            print(tv.shell("cmd package set-home-activity com.google.android.tvlauncher/.MainActivity").strip())
        # AdGuard Private DNS off again (both values were unset before the debloat)
        tv.shell("settings delete global private_dns_specifier")
        tv.shell("settings delete global private_dns_mode")
        LOG.write_text("")
        print("All packages re-enabled. Reboot the box: tools/tv.py reboot")


if __name__ == "__main__":
    main()
