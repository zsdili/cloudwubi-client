#!/usr/bin/env bash
# ============================================================
# CloudWubi 回归自检（机器级"自我记忆"）：发布前自动核查历史反复出错点
# 规则：任何历史反馈过的"低级错误"都必须在此留下锚点，
#       锚点丢失 = CI 红牌 = 阻止发布 → 问题只减不增
# 用法：bash scripts/regression_check.sh [APK路径]
# ============================================================
set -u
IME=android/app/src/main/java/com/cloudwubi/ime/CloudWubiIME.java
VIEW=android/app/src/main/java/com/cloudwubi/ime/CloudKeyboardView.java
FAIL=0

chk() { # chk <描述> <文件> <grep模式>
  if grep -qE "$3" "$2" 2>/dev/null; then
    echo "  ✅ $1"
  else
    echo "  ❌ $1（锚点丢失！）"
    FAIL=1
  fi
}
chk_not() { # chk_not <描述> <文件> <禁止出现的模式>
  if grep -qE "$3" "$2" 2>/dev/null; then
    echo "  ❌ $1（不应出现的内容出现了！）"
    FAIL=1
  else
    echo "  ✅ $1"
  fi
}

echo "== CloudWubi 回归自检 =="

# 1) 上滑标点（！，→！ / ？。→？）——v0.5.17 回归修复
chk "上滑标点：！，键→！" "$VIEW" "case -106: return 0xFF01"
chk "上滑标点：？。键→？" "$VIEW" "case -108: return 0xFF1F"
chk "上滑标点：onKey 接收 0xFF01" "$IME" "case 0xFF01"
# 2) 字母上滑数字/符号（Q→1…）——v0.4.6
chk "字母上滑：Q→1" "$VIEW" "case 113: return '1'"
# 3) 重复上屏根治（宇宇宙 / 8*4=3232）——v0.5.12
chk "重复上屏根治：前缀去重" "$IME" "text.startsWith\(base\)"
# 4) 四码词组优先 + 构词分组——v0.5.16/v0.5.17
chk "词组优先：phrases 先收" "$IME" "optJSONArray\(\"phrases\"\)"
chk "构词分组：gen 排真词组后" "$IME" "optJSONArray\(\"gen\"\)"
# 5) MRU 置顶（最近打过的字调前）——v0.5.9/v0.5.17
chk "MRU 置顶段存在" "$IME" "!lastSelected\.isEmpty\(\)\) \{"
chk_not "MRU 段无 isJustCommitted 过滤（会吃掉最近字）" "$IME" "!lastSelected\.isEmpty\(\) && !isJustCommitted\(lastSelected\)"
# 6) 密码框直通——v0.5.5/v0.5.17
chk "密码框检测" "$IME" "TYPE_TEXT_VARIATION_PASSWORD"
chk "密码框直通块" "$IME" "if \(isPassword\) \{"
chk "密码框 shift 放行（v0.5.21）" "$IME" "v0.5.21 修复"
chk "密码框运算符直通" "$IME" "KEY_CALC_DIV"
# 7) shift 大小写——v0.5.16
chk "shift 大小写" "$IME" "Character\.toUpperCase"
# 8) v0.5.23 动态拼词：基础库全码锚点（拼词源：工作=aaaa+wthf、你好=wqiy+vbg、前进=uejj+fjpk）
chk "动态拼词引擎：buildDynamicWords" "android/app/src/main/java/com/cloudwubi/ime/WubiDbCore.java" "buildDynamicWords"
chk "动态词组编码：dynamicPhraseCode" "android/app/src/main/java/com/cloudwubi/ime/WubiDbCore.java" "dynamicPhraseCode"
chk "保障词数组 GUARANTEED" "android/app/src/main/java/com/cloudwubi/ime/WubiDbCore.java" "GUARANTEED = "
chk "基础库 全码：工=aaaa（拼工作）" "android/app/src/main/assets/wubi_single.txt" "^aaaa工$"
chk "基础库 全码：作=wthf（拼工作）" "android/app/src/main/assets/wubi_single.txt" "^wthf作$"
chk "基础库 全码：你=wqiy（拼你好）" "android/app/src/main/assets/wubi_single.txt" "^wqiy你$"
chk "基础库 全码：好=vbg（拼你好）" "android/app/src/main/assets/wubi_single.txt" "^vb好$"
chk "基础库 全码：前=uejj（拼前进）" "android/app/src/main/assets/wubi_single.txt" "^uejj前$"
chk "基础库 全码：进=fjpk（拼前进）" "android/app/src/main/assets/wubi_single.txt" "^fjpk进$"
chk "基础库 全码：科=tufh（拼科学）" "android/app/src/main/assets/wubi_single.txt" "^tufh科$"
chk "基础库 全码：学=ipbf（拼科学）" "android/app/src/main/assets/wubi_single.txt" "^ipbf学$"
chk "基础库 全码：钟=qkhh" "android/app/src/main/assets/wubi_single.txt" "^qkhh钟$"
chk "基础库 全码：的=rqyy（拼好的）" "android/app/src/main/assets/wubi_single.txt" "^rqyy的$"
chk "基础库 全码：理=gjf（拼大理）" "android/app/src/main/assets/wubi_single.txt" "^gjfg理$"
chk "基础库 全码：我=trnt（拼我们）" "android/app/src/main/assets/wubi_single.txt" "^trnt我$"
chk "基础库 简码：你=wq（二级简码）" "android/app/src/main/assets/wubi_single.txt" "^wq你$"
# 9) 一级简码高频字（的在前）——v0.5.7 反馈⑧/v0.5.17（词库分行格式：a工…r的…y主）
chk "一级简码高频：的" "android/app/src/main/assets/wubi_single.txt" "^r的$"
# 9b) 备选栏高度一致性（v0.5.62：状态规范化根治空/打字切换闪屏）
chk "备选栏状态规范化：resetCandidateStyle" "$IME" "resetCandidateStyle"
chk "备选栏统一复位：render前调用" "$IME" "resetCandidateStyle\\(\\);   // v0.5.62"
# 10) 体积门禁
APK="${1:-}"
if [ -n "$APK" ] && [ -f "$APK" ]; then
  SIZE=$(stat -c%s "$APK")
  if [ "$SIZE" -le 102400 ]; then
    echo "  ✅ 体积门禁：${SIZE}B ≤ 100KB"
  else
    echo "  ❌ 体积超标：${SIZE}B > 100KB"
    FAIL=1
  fi
fi

if [ "$FAIL" = "1" ]; then
  echo "== ❌ 回归自检未通过，禁止发布 =="
  exit 1
fi
echo "== ✅ 回归自检全部通过 =="
