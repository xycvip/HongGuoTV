#!/usr/bin/env bash
# 为 playback-js 的 JVM 本地单测编译 libquickjs.so（linux-x64）。
#
# quickjs-kt 的 -jvm artifact 只有 Kotlin/JNI 桥接类，原生引擎要从 C 源码构建
# （与官方 CMake 构建等价，这里用系统 gcc 原生编译，不跨平台所以不需要 zig）。
# 子模块固定提交与 quickjs-kt v1.0.18 仓库一致。
#
# 产物：libs/native/linux-x64/libquickjs.so
# 单测通过 -Dcom.dokar.quickjs.library.path=<该目录> 加载。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="$ROOT/build/quickjs-native"
OUT_DIR="$ROOT/libs/native/linux-x64"
QUICKJS_COMMIT="04be246001599f5995fa2f2d8c91a0f198d3f34c"
CVECTOR_COMMIT="774773d4cb1e66dd736e90e7f482d4f22af464c6"

mkdir -p "$SRC_DIR" "$OUT_DIR"

clone_at() {
    local url="$1" commit="$2" dir="$3"
    if [ -d "$dir/.git" ] && [ "$(git -C "$dir" rev-parse HEAD)" = "$commit" ]; then
        return 0
    fi
    rm -rf "$dir"
    git init -q "$dir"
    git -C "$dir" remote add origin "$url"
    git -C "$dir" fetch -q --depth 1 origin "$commit"
    git -C "$dir" checkout -q FETCH_HEAD
}

clone_at https://github.com/bellard/quickjs.git "$QUICKJS_COMMIT" "$SRC_DIR/quickjs"
clone_at https://github.com/eteran/c-vector.git "$CVECTOR_COMMIT" "$SRC_DIR/c-vector"

JNI_SRC="$SRC_DIR/../jni-bridge"
# JNI 桥接源码来自 quickjs-kt 仓库（版本对齐 1.0.18 的 native/jni 目录）。
if [ ! -d "$JNI_SRC" ]; then
    git clone -q --depth 1 https://github.com/dokar3/quickjs-kt "$SRC_DIR/quickjs-kt"
    mkdir -p "$JNI_SRC"
    cp -r "$SRC_DIR/quickjs-kt/quickjs/native/jni" "$JNI_SRC/jni"
    cp -r "$SRC_DIR/quickjs-kt/quickjs/native/common" "$JNI_SRC/common"
fi

JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
echo "JAVA_HOME=$JAVA_HOME"

VERSION="$(cat "$SRC_DIR/quickjs/VERSION" | tr -d '[:space:]')"
echo "quickjs VERSION=$VERSION"

CFLAGS="-O2 -fPIC -shared -std=gnu99 -D_GNU_SOURCE -DCONFIG_BIGNUM -DCONFIG_VERSION=\"\\\"$VERSION\\\"\" -w"
INCLUDES="-I$SRC_DIR/quickjs -I$SRC_DIR/c-vector -I$JNI_SRC/common -I$JNI_SRC/jni -I$JNI_SRC/jni/mapping -I$JAVA_HOME/include -I$JAVA_HOME/include/linux"

gcc $CFLAGS $INCLUDES \
    "$SRC_DIR/quickjs/cutils.c" \
    "$SRC_DIR/quickjs/dtoa.c" \
    "$SRC_DIR/quickjs/libregexp.c" \
    "$SRC_DIR/quickjs/libunicode.c" \
    "$SRC_DIR/quickjs/quickjs.c" \
    "$JNI_SRC/common/quickjs_version.c" \
    "$JNI_SRC/common/quickjs_interrupt.c" \
    $JNI_SRC/jni/*.c \
    $JNI_SRC/jni/mapping/*.c \
    -o "$OUT_DIR/libquickjs.so" -lm -ldl -lpthread

echo "built -> $OUT_DIR/libquickjs.so"
ls -la "$OUT_DIR"
