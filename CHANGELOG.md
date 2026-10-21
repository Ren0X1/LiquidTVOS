# Changelog

## [1.0.0.f0]

### ✨ First release
- 🪟 **Liquid Glass home screen** for Android TV: glass dock, pills and focus drawn over the actual photo behind them, with a blur precomputed at build time so the box never blurs at runtime.
- 💧 **Liquid focus**: one glass highlight that stretches towards where it goes and springs back on arrival, with a dark inner ring so it reads on white banners.
- 🇪🇸 **35 photos of Spain** (Wikimedia Commons), rotating every 3 minutes and on every return home, with the place and author above the dock.
- 🌤️ **Weather** from Open-Meteo beside the greeting, refreshed every 30 minutes, retried right after boot and shown from cache instantly.
- ✏️ **Dock editor** on the TV: replace, add or remove dock apps from a glass picker.
- 🗑️ **Free memory** button (closes background apps, reports MB freed; hold for Storage).
- 🎛️ **Remote apps button** opens the app grid from anywhere, through a tiny accessibility service.
- 🌙 **Screensaver**: after a minute the UI fades and only the photo and its place stay.
- 🧭 **Deterministic D-pad**: Up/Down move exactly one row, row ends stop, the last tile of every row is remembered.
- 🔄 Apps installed, removed or updated show up on their own, even while another app is in front.
- 🧹 **Debloat toolkit**: backup, profile-based `pm disable-user`, restore script, ADB-closed check, Mi Box 4 profile.
- 🛠️ **No Gradle**: builds with aapt2 + javac + d8 + apksigner; `tools/setup.py` downloads adb and the SDK on Windows, macOS or Linux.
