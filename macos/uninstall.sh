#!/bin/bash
# ============================================================
# CloudWubi 卸载清理脚本（macOS）
# 清除全部已安装残留：用户级 + 系统级 + 偏好设置 + 下载包
#
# 用法：bash uninstall.sh
# 完成后：注销重登即可彻底消失
# ============================================================
set -e

echo "== 1/5 清除用户级输入法 =="
rm -rf "$HOME/Library/Input Methods/CloudWubi.app" && echo "   ✅ 已删除 ~/Library/Input Methods/CloudWubi.app" || true

echo "== 2/5 清除系统级输入法（需密码）=="
sudo rm -rf "/Library/Input Methods/CloudWubi.app" && echo "   ✅ 已删除 /Library/Input Methods/CloudWubi.app" || true

echo "== 3/5 清除偏好设置 =="
rm -f "$HOME/Library/Preferences/com.cloudwubi.inputmethod.plist" && echo "   ✅ 已删除偏好设置" || true

echo "== 4/5 清除下载的安装包（Downloads 中的 CloudWubi.app）=="
rm -rf "$HOME/Downloads/CloudWubi.app" && echo "   ✅ 已删除 ~/Downloads/CloudWubi.app" || true

echo "== 5/5 清理 LaunchServices 缓存 =="
LSREG="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
"$LSREG" -u "$HOME/Library/Input Methods/CloudWubi.app" 2>/dev/null || true
"$LSREG" -u "/Library/Input Methods/CloudWubi.app" 2>/dev/null || true
echo "   ✅ 已注销（残留缓存由系统自动清理）"

echo ""
echo "======================================================"
echo "✅ 卸载完成！最后一步：注销重登（左上角  → 退出登录 → 重新登录）"
echo "   重新登录后输入法列表将不再出现「云五笔」"
echo "======================================================"
