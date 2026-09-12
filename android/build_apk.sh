#!/bin/bash
# ============================================================
# CloudWubi Android APK 构建脚本（命令行，无 gradle 依赖）
#
# 原理：直接调用 Android SDK 命令行工具链
#   aapt2  -> 编译+打包资源与 Manifest
#   javac  -> 编译 Java 源码（依赖 android.jar）
#   d8     -> 转 dex
#   zipalign + apksigner -> 对齐+签名
#
# 前置：ANDROID_HOME 指向 Android SDK（CI 已预装）
# 用法：bash build_apk.sh
# ============================================================
set -e

SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    echo "❌ 未找到 Android SDK（设置 ANDROID_HOME）"
    exit 1
fi
echo "✅ Android SDK: $SDK"

BUILD_TOOLS="$SDK/build-tools"
BT_VER=$(ls "$BUILD_TOOLS" 2>/dev/null | sort -V | tail -1)
if [ -z "$BT_VER" ]; then
    echo "❌ 未找到 build-tools"
    exit 1
fi
BT="$BUILD_TOOLS/$BT_VER"
PLATFORM="$SDK/platforms"
PLAT_VER=$(ls "$PLATFORM" 2>/dev/null | sort -V | tail -1)
ANDROID_JAR="$PLATFORM/$PLAT_VER/android.jar"
echo "✅ build-tools: $BT_VER | platform: $PLAT_VER"

cd "$(dirname "$0")"
OUT=build-apk
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen"

echo "== 1/6 编译资源（aapt2）=="
"$BT/aapt2" compile \
    --dir app/src/main/res \
    -o "$OUT/res.zip"

echo "== 2/6 链接资源+Manifest =="
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest app/src/main/AndroidManifest.xml \
    --java "$OUT/gen" \
    --min-sdk-version 21 \
    --target-sdk-version 33 \
    "$OUT/res.zip"

echo "== 3/6 编译 Java（javac，含 aapt2 生成的 R.java）=="
find app/src/main/java "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -source 1.8 -target 1.8 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "== 4/6 转 dex（d8）=="
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$BT/d8" --release --lib "$ANDROID_JAR" \
    --output "$OUT" \
    $(cat "$OUT/classes.txt")

echo "== 5/6 打包 dex 进 APK =="
cd "$OUT"
zip -q base.apk classes.dex

echo "== 6/6 对齐 + 签名（debug key）=="
"$BT/zipalign" -f 4 base.apk aligned.apk
# debug keystore（首次生成）
KEYSTORE=debug.keystore
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -v -keystore "$KEYSTORE" \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=CloudWubi, OU=OSS, O=CloudWubi, L=SZ, ST=GD, C=CN" 2>/dev/null
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
    --out CloudWubi.apk aligned.apk

echo ""
echo "== 构建完成 =="
SIZE=$(stat -c %s CloudWubi.apk 2>/dev/null || stat -f %z CloudWubi.apk)
echo "✅ CloudWubi.apk: $SIZE 字节（$(du -h CloudWubi.apk | cut -f1)）"
if [ "$SIZE" -gt 819200 ]; then
    echo "❌ 超过 800KB 上限！"
    exit 1
fi
echo "✅ 体积达标（< 800KB）"
