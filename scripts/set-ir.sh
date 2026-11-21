#!/usr/bin/env bash
# Configures an IR account in the player profile.
#
#   ./scripts/set-ir.sh <ir-name> <user-id> <password> [always|complete|update]
#
# The game has no launcher UI for IR settings, so this script edits the player profile directly.
# IR names come from the bundled plugins, e.g. "mocha" (Mocha-Repository, built in).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
source scripts/device.sh

if [ $# -lt 3 ]; then
  echo "usage: $0 <ir-name> <user-id> <password> [always|complete|update]" >&2
  exit 1
fi
IR_NAME="$1"
USER_ID="$2"
PASSWORD="$3"
SEND="${4:-always}"
case "$SEND" in
  always) IRSEND=0 ;;
  complete) IRSEND=1 ;;
  update) IRSEND=2 ;;
  *) echo "error: send mode must be always, complete or update" >&2; exit 2 ;;
esac

PKG="bms.player.beatoraja"
DATA_DIR="/storage/emulated/0/Android/data/$PKG/files/beatoraja"
CONFIG="$DATA_DIR/player/player1/config_player.json"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

is_adb() { [ -n "${ADB_BIN:-}" ] || command -v adb >/dev/null 2>&1; }
adb_cmd() { if [ -n "${ADB_BIN:-}" ]; then "$ADB_BIN" "$@"; else adb "$@"; fi; }

echo "== stopping app"
if is_adb; then adb_cmd shell am force-stop "$PKG"; else su -c "am force-stop $PKG"; fi

echo "== reading player profile"
if is_adb; then
  adb_cmd exec-out cat "$CONFIG" > "$TMP/config.json"
else
  su -c "cat '$CONFIG'" > "$TMP/config.json"
fi
[ -s "$TMP/config.json" ] || { echo "error: profile not found: $CONFIG (start the app once)" >&2; exit 1; }

echo "== updating IR entry: $IR_NAME"
python3 - "$TMP/config.json" "$IR_NAME" "$USER_ID" "$PASSWORD" "$IRSEND" <<'PYEOF'
import json, sys
path, name, user, password, irsend = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5])
with open(path, encoding="utf-8") as handle:
    config = json.load(handle)
entries = config.get("irconfig") or []
entry = None
for candidate in entries:
    if isinstance(candidate, dict) and candidate.get("irname") == name:
        entry = candidate
        break
if entry is None:
    entry = {"irname": name}
    entries.append(entry)
entry.update({
    "irname": name,
    "userid": user,
    "password": password,
    "cuserid": "",
    "cpassword": "",
    "irsend": irsend,
    "importscore": entry.get("importscore", False),
    "syncReviewLevel": entry.get("syncReviewLevel", False),
    "importrival": entry.get("importrival", True),
})
config["irconfig"] = entries
with open(path, "w", encoding="utf-8") as handle:
    json.dump(config, handle, ensure_ascii=False, indent=1)
print("  accounts:", ", ".join(e.get("irname", "?") for e in entries))
PYEOF

echo "== writing player profile"
if is_adb; then
  adb_cmd shell "cat > '$CONFIG'" < "$TMP/config.json"
else
  APP_UID=$(su -c "stat -c %u '$DATA_DIR'" | tr -d '\r')
  su -c "cat > '$CONFIG'" < "$TMP/config.json"
  su -c "chown ${APP_UID}:${APP_UID} '$CONFIG'"
fi
echo "done. Start the game and it will log in to ${IR_NAME}."
