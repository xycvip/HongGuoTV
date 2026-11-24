#!/usr/bin/env bash
# quickjs-kt 1.0.18 官方 AAR 要求 compileSdk 37（AGP 9.1+），本项目 compileSdk 36。
# 这里只把 aar-metadata.properties 的 minCompileSdk 从 37 改成 36，AAR 其余内容
# （classes.jar / libquickjs.so / AndroidManifest）保持原样，产物落 libs/。
# 用法：scripts/patch-quickjs-aar.sh [版本号]
set -euo pipefail

VERSION="${1:-1.0.18}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/libs"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

AAR_NAME="quickjs-kt-android-${VERSION}.aar"
curl -sfL -o "$WORK/$AAR_NAME" \
  "https://repo1.maven.org/maven2/io/github/dokar3/quickjs-kt-android/${VERSION}/$AAR_NAME"

mkdir -p "$WORK/unpack"
unzip -q "$WORK/$AAR_NAME" -d "$WORK/unpack"
META="$WORK/unpack/META-INF/com/android/build/gradle/aar-metadata.properties"
sed -i 's/^minCompileSdk=.*/minCompileSdk=36/' "$META"

mkdir -p "$LIBS"
(cd "$WORK/unpack" && zip -qr "$LIBS/$AAR_NAME" .)
echo "patched -> $LIBS/$AAR_NAME"
grep minCompileSdk "$META"
