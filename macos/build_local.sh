#!/bin/bash
# ============================================================
# CloudWubi 本机免费构建+安装脚本（macOS）
#
# 用途：解决"输入法列表不显示"问题 —— 无需 $99 开发者证书
# 原理：
#   macOS 13+ 强制输入法必须公证。但【本机 Xcode 构建 + Apple
#   Development 免费签名】属于开发模式，系统接受，无需公证。
#   即：用您自己的 Mac 编译一次，就能装到您自己 Mac 上。
#
# 前置（免费）：
#   1. 安装 Xcode（App Store 免费，约 8GB）
#   2. 打开 Xcode → 设置 → 账户 → 登录您的 Apple ID（免费）
#      （首次会自动生成 Apple Development 证书）
#
# 用法：
#   bash macos/build_local.sh
# ============================================================
set -e

cd "$(dirname "$0")/.."   # 到仓库根目录
echo "== 0/3 检查环境 =="

XCODE_VER=$(xcodebuild -version 2>/dev/null | head -1 || true)
if [ -z "$XCODE_VER" ]; then
    echo "❌ 未安装 Xcode"
    echo "   请先到 App Store 免费下载 Xcode（搜索 Xcode，约 8GB）"
    exit 1
fi
echo "   ✅ Xcode: $XCODE_VER"

CERT=$(security find-identity -v -p codesigning 2>/dev/null | grep "Apple Development" | head -1 | sed 's/.*"\(.*\)".*/\1/')
if [ -z "$CERT" ]; then
    echo "❌ 未找到 Apple Development 证书"
    echo "   请打开 Xcode → 设置(Settings) → 账户(Accounts) → 点 + 登录您的 Apple ID"
    echo "   （免费，登录后自动生成开发证书）"
    exit 1
fi
echo "   ✅ 开发证书: $CERT"

echo ""
echo "== 1/3 编译（本机 clang，Universal 双架构）=="
cd macos
mkdir -p build-macos
clang -std=c99 -ObjC -fobjc-arc -Os -arch x86_64 -arch arm64 -I. -I../src \
    -o build-macos/CloudWubi \
    main.m CloudWubiController.m CWQueryEngine.m \
    ../src/wubi_engine.c ../src/lru_cache.c ../src/http_client.c ../src/json_parser.c \
    -framework Cocoa -framework InputMethodKit -framework Carbon
echo "   ✅ 编译完成"

echo ""
echo "== 2/3 组装 .app + Development 签名 =="
rm -rf CloudWubi.app
mkdir -p CloudWubi.app/Contents/MacOS CloudWubi.app/Contents/Resources
cp build-macos/CloudWubi CloudWubi.app/Contents/MacOS/
cp Info.plist CloudWubi.app/Contents/Info.plist
plutil -lint CloudWubi.app/Contents/Info.plist
codesign --force --deep --sign "$CERT" CloudWubi.app
echo "   ✅ 签名完成: $CERT"

echo ""
echo "== 3/3 安装到 /Library/Input Methods/ =="
sudo rm -rf "/Library/Input Methods/CloudWubi.app"
sudo cp -R CloudWubi.app "/Library/Input Methods/"
xattr -dr com.apple.quarantine "/Library/Input Methods/CloudWubi.app"
echo "   ✅ 已安装（本机开发签名，无需公证）"

echo ""
echo "======================================================"
echo "✅ 全部完成！最后一步（必须）："
echo "   点左上角  → 退出登录 → 重新登录"
echo "   重新登录后：系统设置 → 键盘 → 输入法 → + → 云五笔"
echo "======================================================"
