#!/bin/bash
# ============================================================
# CloudWubi 一键安装脚本（macOS）
# 用途：解决"禁止符号 / 输入法列表不显示"问题
# 原理：
#   1. 去除 Gatekeeper 隔离属性（网络下载应用的拦截原因）
#   2. 拷贝到系统输入法目录
#   3. 提示注销重登（输入法列表刷新的必要条件）
#
# 用法：
#   bash install.sh <CloudWubi.app 所在路径>
#   示例：bash install.sh ~/Downloads/CloudWubi.app
# ============================================================
set -e

APP_PATH="${1:-}"
if [ -z "$APP_PATH" ] || [ ! -d "$APP_PATH" ]; then
    echo "❌ 用法: bash install.sh <CloudWubi.app 路径>"
    echo "   示例: bash install.sh ~/Downloads/CloudWubi.app"
    exit 1
fi

APP_NAME=$(basename "$APP_PATH")
echo "== 1/3 去除 Gatekeeper 隔离属性 =="
xattr -dr com.apple.quarantine "$APP_PATH" 2>/dev/null && echo "   ✅ 隔离属性已清除" || echo "   ⚠️ 无隔离属性（可忽略）"

echo "== 2/3 拷贝到 /Library/Input Methods/ =="
sudo cp -R "$APP_PATH" /Library/Input\ Methods/ && echo "   ✅ 已安装到 /Library/Input Methods/$APP_NAME"

echo "== 3/3 验证签名与架构 =="
codesign -dv "/Library/Input Methods/$APP_NAME" 2>&1 | grep -E "Signature|Format" || echo "   ⚠️ 签名信息读取失败"
lipo -info "/Library/Input Methods/$APP_NAME/Contents/MacOS/"* 2>/dev/null | head -1

echo ""
echo "======================================================"
echo "✅ 安装完成！最后一步（必须）："
echo "   点左上角  → 退出登录 → 重新登录"
echo "   重新登录后：系统设置 → 键盘 → 输入法 → + → 云五笔"
echo "======================================================"
