#!/usr/bin/env bash
# Build the beatoraja Android APK (debug).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE_BIN="${GRADLE_BIN:-}"
if [ -z "$GRADLE_BIN" ]; then
  if [ -x "$ROOT/gradlew" ]; then
    GRADLE_BIN="$ROOT/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="$(command -v gradle)"
  else
    for candidate in "$HOME"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle; do
      if [ -x "$candidate" ]; then GRADLE_BIN="$candidate"; fi
    done
  fi
fi
if [ -z "$GRADLE_BIN" ] || [ ! -x "$GRADLE_BIN" ]; then
  echo "error: gradle not found. Run ./gradlew or set GRADLE_BIN." >&2
  exit 1
fi

GRADLE_ARGS=("assembleDebug" "--console=plain")
# On-device Termux builds run on arm64 Android: AGP's Maven aapt2 is x86_64 Linux, so use the Termux one.
if [ -x /data/data/com.termux/files/usr/bin/aapt2 ]; then
  GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2")
fi
if [ -n "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}" ] && [ ! -f local.properties ]; then
  SDK_DIR="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
  printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties
fi

"$GRADLE_BIN" "${GRADLE_ARGS[@]}" "$@"
APK="$ROOT/build/outputs/apk/debug/beatoraja-debug.apk"
echo "APK: $APK"
ls -lh "$APK"
