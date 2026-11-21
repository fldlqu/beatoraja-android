#!/usr/bin/env bash
# Prepares the IR plugin list from an official beatoraja distribution.
#
# The Android build cannot load JVM plugin JARs directly, so plugin classes are converted to DEX by
# Gradle (task dexIrPlugins). This script extracts the bundled IR implementations from an official
# package into ir/:
#   * <package>/beatoraja.jar       - built-in IR implementation, repacked as mocha-ir.jar
#   * <package>/ir/*.jar            - optional third-party plugins, only with --include-third-party
#
# IR plugins and their APIs have their own terms of use; only bring plugins you are allowed to use.
#
# Usage: ./scripts/prepare-ir.sh [--include-third-party] [/path/to/beatoraja*-full.zip | /path/to/beatoraja.jar]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

INCLUDE_THIRD_PARTY=false
if [ "${1:-}" = "--include-third-party" ]; then
  INCLUDE_THIRD_PARTY=true
  shift
fi
INPUT="${1:-}"
if [ -z "$INPUT" ]; then
  for candidate in \
    /storage/emulated/0/Download/beatoraja*-full.zip \
    "$HOME"/beatoraja-full/beatoraja0.*/beatoraja.jar \
    "$HOME"/beatoraja*.jar; do
    if [ -f "$candidate" ]; then INPUT="$candidate"; break; fi
  done
fi
if [ -z "$INPUT" ] || [ ! -f "$INPUT" ]; then
  echo "error: pass the official beatoraja package (.zip) or beatoraja.jar as the first argument" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p ir

case "$INPUT" in
  *.zip)
    echo "== extracting plugin jars from $(basename "$INPUT")"
    if [ "$INCLUDE_THIRD_PARTY" = true ] && unzip -l "$INPUT" | grep -qE '/ir/[^/]+\.jar$'; then
      echo "== including third-party IR plugin jars from the package (use at your own discretion)"
      unzip -o -j -q "$INPUT" '*/ir/*.jar' -d ir/
    fi
    echo "== extracting built-in IR classes from beatoraja.jar"
    unzip -p "$INPUT" '*/beatoraja.jar' > "$WORK/beatoraja.jar" 2>/dev/null || true
    if [ ! -s "$WORK/beatoraja.jar" ]; then
      echo "error: no beatoraja.jar inside the archive" >&2
      exit 1
    fi
    ;;
  *.jar)
    cp "$INPUT" "$WORK/beatoraja.jar"
    ;;
  *)
    echo "error: unsupported input: $INPUT" >&2
    exit 1
    ;;
esac

if [ -s "$WORK/beatoraja.jar" ]; then
  mkdir -p "$WORK/classes"
  (cd "$WORK/classes" && unzip -o -q "$WORK/beatoraja.jar" \
      'bms/player/beatoraja/ir/MochaIRConnection*' 'bms/player/beatoraja/ir/mocha/*' 2>/dev/null || true)
  if [ -d "$WORK/classes/bms/player/beatoraja/ir/mocha" ]; then
    (cd "$WORK/classes" && jar cf "$ROOT/ir/mocha-ir.jar" bms)
    echo "== wrote ir/mocha-ir.jar (built-in Mocha-Repository)"
  else
    echo "warning: no built-in Mocha IR classes found in beatoraja.jar" >&2
  fi
fi

echo "== ir/ contents:"
ls -lh ir/ || true
echo
echo "Now rebuild: ./scripts/build.sh"
echo "IR plugins in ir/ are converted to DEX and packaged into the APK automatically."
echo "Third-party IR plugins are subject to their own terms of use; this project only loads them as-is."
