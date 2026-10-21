#!/usr/bin/env python3
"""Disable packages one by one (pm disable-user --user 0) and log them.

  tools/debloat.py pkg1 pkg2 ...
  tools/debloat.py -f profiles/mibox4.txt     every package listed in a profile

Every package that ends up disabled is appended to debloat/disabled.txt,
which tools/restore.py reads to undo everything.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

LOG = tv.ROOT / "debloat" / "disabled.txt"


def disabled_now():
    return {l.replace("package:", "").strip() for l in tv.shell("pm list packages -d").splitlines() if l.strip()}


def main():
    tv.ensure_connected()
    LOG.parent.mkdir(exist_ok=True)
    logged = set(LOG.read_text().split()) if LOG.exists() else set()
    args = sys.argv[1:]
    if args[:1] == ["-f"]:
        lines = Path(args[1]).read_text(encoding="utf-8").splitlines()
        args = [l.split("#")[0].strip() for l in lines if l.split("#")[0].strip()]
    for pkg in args:
        # one pm call per shell invocation: chained commands can stop silently
        out = tv.shell(f"pm disable-user --user 0 {pkg}").strip()
        ok = pkg in disabled_now()  # trust the package list, not the exit code
        print(f"{'OK  ' if ok else 'FAIL'} {pkg}  {out}")
        if ok and pkg not in logged:
            with LOG.open("a") as f:
                f.write(pkg + "\n")
            logged.add(pkg)


if __name__ == "__main__":
    main()
