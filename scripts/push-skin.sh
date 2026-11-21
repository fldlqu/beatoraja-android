#!/usr/bin/env bash
# Copy a skin folder from the repository into the app data directory on the device.
#   ./scripts/push-skin.sh ModernChic
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
source scripts/device.sh

SKIN="${1:-ModernChic}"
SRC="$ROOT/skin/$SKIN"
PKG="bms.player.beatoraja"
DATA_DIR="/storage/emulated/0/Android/data/$PKG/files/beatoraja"

[ -d "$SRC" ] || { echo "error: skin not found: $SRC" >&2; exit 1; }

if [ -n "${ADB_BIN:-}" ] || command -v adb >/dev/null 2>&1; then
  ADB="${ADB_BIN:-adb}"
  "$ADB" shell mkdir -p "$DATA_DIR/skin"
  "$ADB" push "$SRC" "$DATA_DIR/skin/" >/dev/null
else
  APP_UID=$(su -c "stat -c %u /storage/emulated/0/Android/data/$PKG" 2>/dev/null || echo "")
  su -c "mkdir -p '$DATA_DIR/skin' && rm -rf '$DATA_DIR/skin/$SKIN' && cp -r '$SRC' '$DATA_DIR/skin/' && chown -R ${APP_UID:-$(id -u)}:${APP_UID:-$(id -g)} '$DATA_DIR' && echo uploaded"
fi
echo "skin pushed: $DATA_DIR/skin/$SKIN"
