#!/usr/bin/env python3
"""Build the launcher APK without Gradle and install it on the TV.

  tools/build_launcher.py            build + install + make it the default Home
  tools/build_launcher.py --build    build only (launcher/build/liquidtvos.apk)

Needs a JDK (17+) and an Android SDK with a platform >= 33 and build-tools:
ANDROID_HOME / ANDROID_SDK_ROOT, or android-sdk/ in the repo (tools/setup.py
downloads it). Optional local.json values:
  "weather_city"  town for the weather (else guessed from the IP)
Signing: launcher/debug.keystore is created on first build. Set
LIQUIDTVOS_KEYSTORE (+ LIQUIDTVOS_KEYSTORE_PASS) to sign with your own key.
"""
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tv  # noqa: E402

ROOT = tv.ROOT
SRC = ROOT / "launcher"
OUT = SRC / "build"
EXE = ".exe" if os.name == "nt" else ""
BAT = ".bat" if os.name == "nt" else ""
PKG = "com.alex.tvhome"
HOME = f"{PKG}/.HomeActivity"
A11Y = f"{PKG}/{PKG}.KeyService"


def find_sdk():
    for cand in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), ROOT / "android-sdk"):
        if cand and Path(cand).is_dir():
            return Path(cand)
    sys.exit("Android SDK not found: run `python tools/setup.py` or set ANDROID_HOME.")


def newest(folder, pattern, minimum=0):
    best = None
    for d in folder.glob("*"):
        m = re.match(pattern, d.name)
        if m and d.is_dir():
            key = tuple(int(x) for x in m.groups())
            if key[0] >= minimum and (best is None or key > best[0]):
                best = (key, d)
    if not best:
        sys.exit(f"Nothing matching {pattern} in {folder}: run `python tools/setup.py`.")
    return best[1]


SDK = find_sdk()
BT = newest(SDK / "build-tools", r"(\d+)\.(\d+)\.(\d+)$", 30)
JAR = newest(SDK / "platforms", r"android-(\d+)$", 33) / "android.jar"


def jdk_tool(name):
    found = shutil.which(name)
    if found:
        return found
    home = os.environ.get("JAVA_HOME")
    if not home:
        r = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True)
        for line in r.stderr.splitlines():
            if "java.home" in line:
                home = line.split("=", 1)[1].strip()
    return str(Path(home) / "bin" / (name + EXE)) if home else name


def run(*cmd):
    r = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"FAILED: {' '.join(map(str, cmd))}\n{r.stdout}{r.stderr}")
    return r.stdout


def local_overlay():
    """Values from local.json that override res/values (kept out of git)."""
    cfg = tv.local_config()
    city = cfg.get("weather_city", "")
    if not city:
        return None
    d = OUT / "local-res" / "values"
    d.mkdir(parents=True)
    (d / "local.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <string name="weather_city">{escape(city)}</string>\n</resources>\n', encoding="utf-8")
    return OUT / "local-res"


def keystore():
    custom = os.environ.get("LIQUIDTVOS_KEYSTORE")
    if custom:
        return Path(custom), os.environ.get("LIQUIDTVOS_KEYSTORE_PASS", "android")
    ks = SRC / "debug.keystore"
    if not ks.exists():
        run(jdk_tool("keytool"), "-genkeypair", "-keystore", ks, "-storepass", "android", "-keypass", "android",
            "-alias", "debug", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=LiquidTVOS Debug")
    return ks, "android"


def build():
    shutil.rmtree(OUT, ignore_errors=True)
    (OUT / "gen").mkdir(parents=True)
    (OUT / "classes").mkdir()
    run(BT / f"aapt2{EXE}", "compile", "--dir", SRC / "res", "-o", OUT / "res.zip")
    link = [BT / f"aapt2{EXE}", "link", "-I", JAR, "--manifest", SRC / "AndroidManifest.xml",
            "-A", SRC / "assets", "--java", OUT / "gen", "-o", OUT / "unsigned.apk", OUT / "res.zip"]
    overlay = local_overlay()
    if overlay:
        run(BT / f"aapt2{EXE}", "compile", "--dir", overlay, "-o", OUT / "local.zip")
        link += ["-R", OUT / "local.zip"]
    run(*link)
    sources = [str(p) for p in (SRC / "src").rglob("*.java")] + [str(p) for p in (OUT / "gen").rglob("*.java")]
    run(jdk_tool("javac"), "-source", "11", "-target", "11", "-nowarn", "-Xlint:-options", "-encoding", "UTF-8",
        "-classpath", JAR, "-d", OUT / "classes", *sources)
    classes = [str(p) for p in (OUT / "classes").rglob("*.class")]
    run(BT / f"d8{BAT}", "--release", "--min-api", "26", "--lib", JAR, "--output", OUT, *classes)
    with zipfile.ZipFile(OUT / "unsigned.apk", "a") as z:
        z.write(OUT / "classes.dex", "classes.dex")
    run(BT / f"zipalign{EXE}", "-f", "-p", "4", OUT / "unsigned.apk", OUT / "aligned.apk")
    ks, password = keystore()
    apk = OUT / "liquidtvos.apk"
    run(BT / f"apksigner{BAT}", "sign", "--ks", ks, "--ks-pass", f"pass:{password}",
        "--out", apk, OUT / "aligned.apk")
    print(f"built {apk} ({apk.stat().st_size // 1024} KB)")
    return apk


def install(apk):
    tv.ensure_connected()
    # push + pm install: adb install can hang waiting for an on-screen prompt
    tv.adb("push", str(apk), "/data/local/tmp/liquidtvos.apk", check=True)
    out = tv.shell("pm install -r /data/local/tmp/liquidtvos.apk").strip()
    tv.shell("rm /data/local/tmp/liquidtvos.apk")
    print("install:", out)
    if "Success" not in out:
        sys.exit(1)
    # network name in the Wi-Fi pill needs location permission on Android 9+
    tv.shell(f"pm grant {PKG} android.permission.ACCESS_FINE_LOCATION")
    # the remote's apps button is caught by an accessibility service
    enabled = tv.shell("settings get secure enabled_accessibility_services").strip()
    if A11Y not in enabled:
        services = A11Y if enabled in ("", "null") else enabled + ":" + A11Y
        tv.shell(f"settings put secure enabled_accessibility_services {services}")
        tv.shell("settings put secure accessibility_enabled 1")
    print("set-home:", tv.shell(f"cmd package set-home-activity {HOME}").strip())
    tv.shell("am force-stop " + PKG)
    tv.shell("input keyevent KEYCODE_HOME")


if __name__ == "__main__":
    apk = build()
    if "--build" not in sys.argv:
        install(apk)
