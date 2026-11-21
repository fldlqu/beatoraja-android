#!/usr/bin/env bash
# Point the Android app's player profile at a skin (default state mapping) and optionally patch config.
#   ./scripts/select-skin.sh ModernChic
#   ./scripts/select-skin.sh default
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
source scripts/device.sh

SKIN="${1:-ModernChic}"
PKG="bms.player.beatoraja"
DATA_DIR="/storage/emulated/0/Android/data/$PKG/files/beatoraja"
PLAYER_DIR="$DATA_DIR/player/player1"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

device_shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 1

if [ -n "${ADB_BIN:-}" ] || command -v adb >/dev/null 2>&1; then
  ADB="${ADB_BIN:-adb}"
  "$ADB" pull "$PLAYER_DIR/config_player.json" "$TMP/config_player.json" >/dev/null
else
  su -c "cat '$PLAYER_DIR/config_player.json'" > "$TMP/config_player.json"
fi
[ -s "$TMP/config_player.json" ] || { echo "error: player config not found on device" >&2; exit 1; }

python3 - "$TMP/config_player.json" "$SKIN" <<'PY'
import json, sys
path, skin = sys.argv[1], sys.argv[2]
data = json.load(open(path, encoding='utf-8'))
if skin == 'default':
    mapping = {
        0: 'skin/default/play/play7.luaskin',
        1: 'skin/default/play5.json',
        2: 'skin/default/play14.json',
        3: 'skin/default/play10.json',
        4: 'skin/default/play9.json',
        5: 'skin/default/select.json',
        6: 'skin/default/decide/decide.luaskin',
        7: 'skin/default/result/result.luaskin',
        8: 'skin/default/keyconfig/keyconfig.luaskin',
        9: 'skin/default/skinselect/skinselect.luaskin',
        15: 'skin/default/graderesult.json',
    }
else:
    base = 'skin/%s/' % skin
    mapping = {
        0: base + 'play7_hw.luaskin',
        1: base + 'play5_hw.luaskin',
        2: base + 'play14_hw.luaskin',
        3: base + 'play10_hw.luaskin',
        5: base + 'musicselect.luaskin',
        6: base + 'decide.luaskin',
        7: base + 'result.luaskin',
        8: base + 'keyconfig.luaskin',
        9: base + 'skinselect.luaskin',
        15: base + 'course.luaskin',
    }
entries = data.get('skin') or []
while len(entries) < 19:
    entries.append(None)
for index, target in mapping.items():
    existing = entries[index] if isinstance(entries[index], dict) else {}
    entries[index] = {'path': target}
data['skin'] = entries
data.pop('skinHistory', None)
data.pop('rightSideSkin', None)
data.pop('rightSideSkinHistory', None)
json.dump(data, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print("patched", sum(1 for e in entries if isinstance(e, dict)), "skin entries for", skin)
PY

if [ -n "${ADB_BIN:-}" ] || command -v adb >/dev/null 2>&1; then
  ADB="${ADB_BIN:-adb}"
  "$ADB" push "$TMP/config_player.json" "$PLAYER_DIR/config_player.json" >/dev/null
else
  APP_UID=$(su -c "stat -c %u '$DATA_DIR'" 2>/dev/null | tr -d '\r')
  su -c "cat '$TMP/config_player.json' > '$PLAYER_DIR/config_player.json' && chown ${APP_UID:-u0_a382}:${APP_UID:-u0_a382} '$PLAYER_DIR/config_player.json'"
fi
echo "skin selected: $SKIN"
