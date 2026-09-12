#!/bin/bash
# ============================================================
# CloudWubi 一键安装脚本（macOS）
# 用途：解决"禁止符号 / 输入法列表不显示"问题
# 原理：
#   1. 去除 Gatekeeper 隔离属性（网络下载应用的拦截原因）
#   2. 安装到用户级输入法目录 ~/Library/Input Methods/
#      （参考已验证先例 SwiftType：用户级目录 + ad-hoc 签名 + 未公证可正常安装使用）
#   3. 提示注销重登（输入法列表刷新的必要条件）
#
# 用法：
#   bash install.sh <CloudWubi.app 所在路径> [system|user]
#   示例：bash install.sh ~/Downloads/CloudWubi.app        （默认用户级）
#         bash install.sh ~/Downloads/CloudWubi.app system （系统级，需密码）
# ============================================================
set -e

APP_PATH="${1:-}"
MODE="${2:-user}"

if [ -z "$APP_PATH" ] || [ ! -d "$APP_PATH" ]; then
    echo "❌ 用法: bash install.sh <CloudWubi.app 路径> [system|user]"
    echo "   示例: bash install.sh ~/Downloads/CloudWubi.app"
    exit 1
fi

APP_NAME=$(basename "$APP_PATH")
USER_DIR="$HOME/Library/Input Methods"
SYS_DIR="/Library/Input Methods"

echo "== 1/4 去除 Gatekeeper 隔离属性 =="
xattr -dr com.apple.quarantine "$APP_PATH" 2>/dev/null && echo "   ✅ 隔离属性已清除" || echo "   ⚠️ 无隔离属性（可忽略）"

if [ "$MODE" = "system" ]; then
    INSTALL_DIR="$SYS_DIR"
    echo "== 2/4 拷贝到系统级 $SYS_DIR（需密码）=="
    sudo rm -rf "$SYS_DIR/$APP_NAME"
    sudo cp -R "$APP_PATH" "$SYS_DIR/"
else
    INSTALL_DIR="$USER_DIR"
    echo "== 2/4 拷贝到用户级 $USER_DIR（无需密码）=="
    mkdir -p "$USER_DIR"
    rm -rf "$USER_DIR/$APP_NAME"
    cp -R "$APP_PATH" "$USER_DIR/"
fi
echo "   ✅ 已安装到 $INSTALL_DIR/$APP_NAME"

echo "== 3/4 再次清除目标目录隔离属性 =="
xattr -dr com.apple.quarantine "$INSTALL_DIR/$APP_NAME" 2>/dev/null && echo "   ✅ 完成" || true

echo "== 4/4 验证签名与架构 =="
codesign -dv "$INSTALL_DIR/$APP_NAME" 2>&1 | grep -E "Signature|Format" || echo "   ⚠️ 签名信息读取失败"
lipo -info "$INSTALL_DIR/$APP_NAME/Contents/MacOS/"* 2>/dev/null | head -1

echo ""
echo "======================================================"
echo "✅ 安装完成！最后一步（必须）："
echo "   点左上角  → 退出登录 → 重新登录"
echo "   重新登录后：系统设置 → 键盘 → 输入法 → + → 云五笔"
echo "   （若仍不显示，可重试：sudo rm -rf \"/Library/Input Methods/$APP_NAME\" 清理系统级残留）"
echo "======================================================"
