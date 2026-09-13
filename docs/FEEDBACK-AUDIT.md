# 反馈与用户习惯核查清单（FEEDBACK-AUDIT）

> 固化机制：**每个版本发布前，必须逐条核对下表并更新状态**；未落实项不得发布。
> 依据：钟总历次真机反馈 + 固化规则 + 五笔86 规范（一级→二级简码→单字→词组→联想）。

## 一、排序与联想规则（用户固化核心，先科学后先进）

| # | 规则/要求 | 代码位置 | 状态 |
|---|----------|---------|------|
| 1 | 1 码出高频单字（一级简码 25 字） | WubiDb.query len==1 → singles | ✅ 落实 |
| 2 | 2 码先出二字高频单字，再出二字词组 | WubiDb.query len==2：singles 先、phr 后 | ✅ 落实 |
| 3 | 3 码提示第 4 码高频词组（预测） | WubiDb.queryPredict（3 码扫 4 码词组前缀） | ✅ 落实 |
| 4 | 4 码词组优先（词组在单字前） | WubiDb.query len==4：phr 先、singles 后 | ✅ 落实 |
| 5 | 上次选中的字/词置顶（MRU），须编码匹配 | CloudWubiIME ① 段 lastSelected + recentPhrases | ✅ 落实 |
| 6 | 传统五笔第二位（MRU 与热点中间） | CloudWubiIME ② 段 local（五笔），③④ 后 | ✅ 落实 |
| 7 | 云端热点词组（异步回填，五笔之后） | queryGatewayAsync → cloudInsertPos | ✅ 落实 |
| 8 | 整码(4码)本地无词组 → 云端词组置顶 | v0.5.6：pos=0 且仅词组（len≥2） | ✅ 落实 |
| 9 | 上屏字/词不重复进备选（仅滤最近一次） | committedLast + isJustCommitted 四路过滤 | ✅ 落实 |
| 10 | 光标前字联想（删字定位后同样联想） | enterAssociateChar + showAssociateForChar | ✅ 落实 |
| 11 | 连续联想（陈→胜→吴广式词组链） | associateActive 拼接 + triggerAssociate | ✅ 落实 |
| 12 | 进入输入状态即联想（识别光标前一字） | onStartInputView → enterAssociateChar | ✅ 落实 |
| 13 | 1 秒无输入清空备选栏（保留编码） | idleClearRunnable（1000ms） | ✅ 落实 |
| 14 | 选字后云端英文翻译提示（EN: xxx） | queryTranslation → lastEnHint 渲染 | ✅ 落实 |

## 二、键盘与面板（布局/交互）

| # | 规则/要求 | 代码位置 | 状态 |
|---|----------|---------|------|
| 1 | 10/9/9/7 键行主键盘 | res/xml/keyboard_qwerty.xml | ✅ 落实 |
| 2 | 留边、圆角、FLAT 无立体 | key_bg.xml 圆角 + 无阴影 | ✅ 落实 |
| 3 | 主题跟随系统深浅色 | dark() 判断 + THEME_* 双色 | ✅ 落实 |
| 4 | 键面文字/上滑字符全水平居中 | CloudKeyboardView 中轴绘制 | ✅ 落实 |
| 5 | 中文模式键盘字母大写 | applyLetterCase：chineseMode → 大写 | ✅ 落实 |
| 6 | Shift 单击一次大写/双击锁定大写 | handleShift（350ms 双击） | ✅ 落实 |
| 7 | 主键盘 `！，`/`？。` 上下双行（上滑=！？） | keyboard_qwerty 双行 label + 上滑 | ✅ 落实 |
| 8 | 空格键：有候选上首选，无候选才空格 | KEY_MIC 分支 | ✅ 落实 |
| 9 | 回车 ↲ 标识；单行框回车无反应；长按换行 | KB_ENTER + isMultiline + 长按 600ms | ✅ 落实 |
| 10 | 数字面板 5 键带运算，实时计算，可续算 | calcBuffer + lastCalcResult | ✅ 落实 |
| 11 | 符号面板三页+翻页循环+每页退格+返回逐层 | sym/sym2/sym3 + KEY_SYM_PREV + prevPanel | ✅ 落实 |
| 12 | 面板满宽居中，返回统一左下角"返回"文字 | keyboard_num/sym 布局 | ✅ 落实 |
| 13 | 上滑输入数字/标点（键面无上滑标注） | CloudKeyboardView 上滑手势 | ✅ 落实 |

## 三、工具行与功能

| # | 规则/要求 | 代码位置 | 状态 |
|---|----------|---------|------|
| 1 | 亖剪贴板置工具行最前；密码框禁用 | toolRow 亖 + isPassword 拦截 | ✅ 落实 |
| 2 | 全选 / 取消↺ / 重做↻ | toolRow selectAll/doUndo/doRedo | ✅ 落实 |
| 3 | 剪贴板历史：仅复制文本，最新在前，2 倍行距+灰线 | clipList 倒序 + 行距 | ✅ 落实 |
| 4 | 2 秒未输入 → 收起键盘按钮（INVISIBLE 占位不跳动） | idleRunnable + hideBtn | ✅ 落实 |
| 5 | 全键盘/各状态无语音图标与功能 | 已全面移除 | ✅ 落实 |
| 6 | 编码实时回显（候选条弱色前缀） | renderCandidates encColor | ✅ 落实 |
| 7 | 备选栏纯净（无编码括号、无多余提示） | v0.5.3 已去除 | ✅ 落实 |
| 8 | 版本号+作者钟志胜+QQ175571 在应用信息界面 | activity_about.xml | ✅ 落实 |

## 四、隐私与安全

| # | 规则/要求 | 代码位置 | 状态 |
|---|----------|---------|------|
| 1 | 密码框可正常输入（不吞字） | onStartInput + commitText 先 finishComposingText | ✅ 落实 |
| 2 | 密码框禁用联想/剪贴板/翻译 | isPassword 全拦截 | ✅ 落实 |
| 3 | 固定签名可覆盖安装 | android/keystore/cloudwubi.jks | ✅ 落实 |

## 五、体积与质量门禁

| # | 规则/要求 | 代码位置 | 状态 |
|---|----------|---------|------|
| 1 | APK ≤ 100KB | build_apk.sh 门禁（超限 exit 1） | ✅ 落实 |
| 2 | APK 内必须含词库 assets | build_apk.sh 自检（缺则失败） | ✅ 落实 |
| 3 | R8 混淆+裁剪（省体积） | build_apk.sh R8 多路径探测 | ✅ 落实 |
| 4 | 词库编码必须经 rules 码表核实 | 扩容程序 + rule_verify.py | ✅ 落实 |

> 更新记录：v0.5.7 建立本清单（2026-09-13）；后续每版发布前核对更新。
