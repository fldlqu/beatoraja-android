#!/usr/bin/env bash
# Shared helper: run a command on the Android device.
# Prefers adb (desktop hosts / wireless debugging); falls back to root `su -c` on Termux.
set -euo pipefail

device_exec() {
  if [ -n "${ADB_BIN:-}" ]; then
    "$ADB_BIN" "$@"
  elif command -v adb >/dev/null 2>&1; then
    adb "$@"
  elif command -v su >/dev/null 2>&1 && su -c true >/dev/null 2>&1; then
    su -c "$*"
  else
    echo "error: neither adb nor root su available to reach the Android device" >&2
    return 1
  fi
}

# Run a raw shell command string on the device.
device_shell() {
  if [ -n "${ADB_BIN:-}" ]; then
    "$ADB_BIN" shell "$@"
  elif command -v adb >/dev/null 2>&1; then
    adb shell "$@"
  elif command -v su >/dev/null 2>&1; then
    su -c "$*"
  else
    echo "error: neither adb nor root su available to reach the Android device" >&2
    return 1
  fi
}

# install APK
device_install() {
  local apk="$1"
  if [ -n "${ADB_BIN:-}" ]; then
    "$ADB_BIN" install -r "$apk"
  elif command -v adb >/dev/null 2>&1; then
    adb install -r "$apk"
  else
    su -c "pm install -r '$apk'"
  fi
}
