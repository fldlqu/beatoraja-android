#!/usr/bin/env bash
# Build a shareable distribution archive containing the APK and a skin folder.
#
#   ./scripts/package.sh [--skin <skin-dir>] [--apk <apk>] [--out <zip>]
#
# Defaults: skin/ModernChic, the debug APK, and
# /storage/emulated/0/Download/beatoraja-android-<skin>.zip.
#
# Archive layout:
#   beatoraja-android.apk
#   skin/<skin-name>/...
#   安装说明.md
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SKIN_DIR="skin/ModernChic"
APK="build/outputs/apk/debug/beatoraja-debug.apk"
OUT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --skin) SKIN_DIR="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "error: unknown option: $1" >&2; exit 2 ;;
  esac
done

[ -d "$SKIN_DIR" ] || { echo "error: skin directory not found: $SKIN_DIR" >&2; exit 1; }
[ -f "$APK" ] || { echo "error: APK not found: $APK (run ./scripts/build.sh first)" >&2; exit 1; }
SKIN_NAME="$(basename "$SKIN_DIR")"
if [ -z "$OUT" ]; then
  OUT="/storage/emulated/0/Download/beatoraja-android-${SKIN_NAME}.zip"
fi
mkdir -p "$(dirname "$OUT")"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/安装说明.md" <<'TEXT'
# beatoraja Android 发行包

本压缩包包含：

- `beatoraja-android.apk`：Android 版游戏（debug 构建，arm64-v8a，Android 8.0+）
- `skin/ModernChic/`：ModernChic 皮肤及其音效资源

## 安装 APK

直接用文件管理器安装 `beatoraja-android.apk`。打开应用会先进入设置/启动界面，
点“进入游戏”开始游戏；首次进入游戏会自动解包默认数据并扫描曲库。

## 安装 ModernChic 皮肤

目标目录：
`/storage/emulated/0/Android/data/bms.player.beatoraja/files/beatoraja/skin/ModernChic`

方式 A（adb）：

```bash
adb push skin/ModernChic /sdcard/Android/data/bms.player.beatoraja/files/beatoraja/skin/
```

方式 B（Termux + root）：

```bash
su -c "mkdir -p /storage/emulated/0/Android/data/bms.player.beatoraja/files/beatoraja/skin && cp -r skin/ModernChic /storage/emulated/0/Android/data/bms.player.beatoraja/files/beatoraja/skin/"
```

方式 C（文件管理器）：先在电脑/手机上解压 `skin/ModernChic`，再通过支持
“Android/data 访问”的文件管理器复制到目标目录。

安装完成后进入游戏 → **SKIN CONFIG** 界面按皮肤类型选择 ModernChic
（也可以使用仓库脚本 `scripts/select-skin.sh ModernChic` 写入玩家配置）。

## 注意

- 皮肤目录约 760MB，复制需要一些时间。
- 游戏数据（配置、曲库、成绩）位于同一个 `beatoraja` 目录，升级 APK 不会丢失。
- 该 APK 为端侧调试构建，未上架应用商店。
TEXT

rm -f "$OUT"
# Store already-compressed media without spending CPU on recompression.
cp "$APK" "$WORK/beatoraja-android.apk"
zip -0 -q -j "$OUT" "$WORK/beatoraja-android.apk"
zip -0 -q -r "$OUT" "$SKIN_DIR"
zip -0 -q -j "$OUT" "$WORK/安装说明.md"

SHA="$(sha256sum "$OUT" | awk '{print $1}')"
SIZE="$(du -h "$OUT" | awk '{print $1}')"
echo "package: $OUT"
echo "size:    $SIZE"
echo "sha256:  $SHA"
echo
echo "top-level contents:"
unzip -l "$OUT" | awk 'NR>3 && NF>=4 {print $4}' | sed -E 's#^([^/]+/)?([^/]+).*#\1\2#' | sort -u
