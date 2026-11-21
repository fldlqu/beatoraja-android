#!/usr/bin/env bash
# Install + launch the app, drive the debug bridge, capture screenshots and logcat.
#
#   ./scripts/run.sh [apk] [-- <debug bridge commands>]
#
# Requires adb (desktop) or root su (Termux on-device). The app exposes a debug bridge:
#   am broadcast -a bms.player.beatoraja.CAPTURE
#   am broadcast -a bms.player.beatoraja.KEY --ei key 19 --ez down true
#   am broadcast -a bms.player.beatoraja.TAP --ef x 1200 --ef y 700
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
source scripts/device.sh

APK="${1:-$ROOT/build/outputs/apk/debug/beatoraja-debug.apk}"
PKG="bms.player.beatoraja"
ACTIVITY="$PKG/.Launcher"
OUT_DIR="${OUT_DIR:-$ROOT/screenshots}"
WAIT_SECONDS="${WAIT_SECONDS:-20}"
DATA_DIR_DEFAULT="/storage/emulated/0/Android/data/$PKG/files/beatoraja"
DATA_DIR="${DATA_DIR:-$DATA_DIR_DEFAULT}"
mkdir -p "$OUT_DIR"

if [ ! -f "$APK" ]; then echo "error: APK not found: $APK (run scripts/build.sh)" >&2; exit 1; fi

is_adb() { [ -n "${ADB_BIN:-}" ] || command -v adb >/dev/null 2>&1; }
adb_cmd() { if [ -n "${ADB_BIN:-}" ]; then "$ADB_BIN" "$@"; else adb "$@"; fi; }

echo "== install =="
device_install "$APK"
echo "== clear logcat =="
device_shell logcat -c >/dev/null 2>&1 || true
echo "== launch =="
device_shell am force-stop "$PKG" >/dev/null 2>&1 || true
device_shell am start -W -n "$ACTIVITY"
sleep "$WAIT_SECONDS"

echo "== request framebuffer capture =="
device_shell am broadcast -a bms.player.beatoraja.CAPTURE >/dev/null 2>&1 || true
sleep 2

echo "== logcat =="
device_shell logcat -d -s beatoraja:V AndroidRuntime:E libGDX:I System.err:W 2>/dev/null | tail -200 | tee "$OUT_DIR/logcat.txt" || true

echo "== collect screenshots =="
if is_adb; then
  adb_cmd pull "$DATA_DIR/screenshots" "$OUT_DIR/" >/dev/null 2>&1 || true
else
  # Root can read the app-private external dir even when the app is not debuggable.
  su -c "cp -f '$DATA_DIR'/screenshots/*.png '$OUT_DIR'/ 2>/dev/null; true" || true
  su -c "chown $(id -u):$(id -g) '$OUT_DIR'/*.png 2>/dev/null; true" || true
fi
ls -lh "$OUT_DIR" | tail -5
