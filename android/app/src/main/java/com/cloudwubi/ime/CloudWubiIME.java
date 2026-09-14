package com.cloudwubi.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudWubiIME - 云五笔 Android 输入法服务（v0.4.8）
 *
 * v0.4.8 修复清单（真机第四轮 8 项反馈）：
 *   ① 词组智能联想：上屏单字后，最近 3 个选中词组置顶 + 带此字的词组（本地+云端热点）
 *   ② 退格：全选/部分选中时正确删除选区（此前全选无反应、部分选中只删末字）
 *   ③ 剪贴板历史行间距加大，易点选
 *   ④ 回车键无候选时上屏换行（此前误上屏空格）
 *   ⑤ 🎤 键改为空格键（上屏空格/选首选），移除语音占位提示
 *   ⑥ 键帽深色边框改为羽化渐变边框
 *   ⑦ 编码括号移至候选末尾（1.钟 2.鈡（qkhh））；选字后云端提示英文翻译
 *   ⑧ 数字面板 4 列（左侧 + - × ÷），实时计算并默认「带公式上屏」，长按 = 仅上屏结果
 */
public class CloudWubiIME extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    /** 云端网关地址（腾讯云 SCF 函数 URL，v0.4.7 启用） */
    private static final String GATEWAY_URL =
            "https://1251037126-bglnivgmaf.ap-guangzhou.tencentscf.com";
    private static final boolean GATEWAY_READY = true;

    // ===== 功能键编码（与 XML 严格对应，v0.4.6 按截图 / v0.4.8 数字面板） =====
    private static final int KEY_123 = -101;      // 数字面板
    private static final int KEY_LANG = -102;     // 中/英
    private static final int KEY_SYMBOL = -103;   // 面板返回主键盘
    private static final int KEY_SYM_IN = -104;   // 符号面板
    // v0.5.34 符号面板分类式（参考用户截图：最近/中文/英文/表情/网络 + 底部导航）
    private static final int KEY_SYM_RECENT = -311;
    private static final int KEY_SYM_CN = -312;
    private static final int KEY_SYM_EN = -313;
    private static final int KEY_SYM_EMOJI = -314;
    private static final int KEY_SYM_NET = -315;
    private static final int KEY_SYM_LOCK = -321;
    private static final int KEY_SYM_UP = -322;
    private static final int KEY_SYM_DOWN = -323;
    private static final int KEY_NET_COM = -341;
    private static final int KEY_NET_NET = -342;
    private static final int KEY_NET_CN = -343;
    private static final int KEY_NET_WWW = -344;
    private static final int KEY_NET_HTTP = -345;
    private static final int KEY_NET_HTTPS = -346;
    private static final int KEY_NET_SLASH2 = -347;
    private static final int KEY_NET_COMSLASH = -348;
    private static final int KEY_SHIFT = -105;    // ↑ Shift（单击切换/双击锁定大写）
    private static final int KEY_PUNCT_BANG = -106; // !，双标点循环
    private static final int KEY_MIC = -107;      // v0.4.8: 空格键（原🎤语音占位已移除）
    private static final int KEY_PUNCT_QM = -108; // ？。双标点循环
    private static final int KEY_CALC_EQ = -201;  // 数字面板 = 计算上屏
    private static final int KEY_CALC_DIV = -202; // 数字面板 ÷
    private static final int KEY_CALC_MUL = -203; // 数字面板 ×
    private static final int KEY_UNDO = -209;     // v0.4.9 取消↺
    private static final int KEY_REDO = -210;     // v0.4.9 重做↻
    private static final int KEY_SEARCH = -211;   // v0.5.0 搜索键（数字面板）
    private static final int KEY_SPACE = 32;
    private static final int KB_DELETE = -5;      // Keyboard.KEYCODE_DELETE
    private static final int KB_ENTER = -4;       // Keyboard.KEYCODE_ENTER

    // ===== v0.4.9 FLAT 主题（跟随系统浅色/深色） =====
    private static final int THEME_LIGHT = 0;
    private static final int THEME_DARK = 1;
    private static final int THEME_LIGHT_KB_BG = 0xFFE8EBEF;   // 键盘底色（浅）
    private static final int THEME_LIGHT_CAND_BG = 0xFFFFFFFF; // 候选条背景（浅）
    private static final int THEME_LIGHT_TEXT = 0xFF1F2937;    // 主文字（浅）
    private static final int THEME_LIGHT_HINT = 0xFF9CA3AF;    // 上滑标注/弱文字（浅）
    private static final int THEME_DARK_KB_BG = 0xFF1B1F24;    // 键盘底色（深）
    private static final int THEME_DARK_CAND_BG = 0xFF23272E;  // 候选条背景（深）
    private static final int THEME_DARK_TEXT = 0xFFF3F4F6;     // 主文字（深）
    private static final int THEME_DARK_HINT = 0xFF6B7280;     // 弱文字（深）
    private static final int THEME_ACCENT = 0xFF3B82F6;        // 主题蓝
    private static final int THEME_FIRST = 0xFFF59E0B;         // 首选橙色
    private static final int THEME_ERROR = 0xFFDC2626;         // 计算错误红
    private int theme = THEME_LIGHT;

    // ===== v0.4.9 连续联想（陈→胜→吴广） =====
    private String lastCommittedText = "";   // 最近上屏的连续文本（联想链拼接）

    // ===== v0.4.9 候选翻页 =====
    private static final int CAND_PAGE_SIZE = 5;
    private int candPage = 0;
    private int pinyinInsertPos = 0;   // v0.5.36 反馈⑨：拼音候选插入位置（本地五笔候选之后）

    // ===== v0.4.9 取消↺ / 重做↻（编辑快照栈） =====
    private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> redoStack = new java.util.ArrayDeque<>();
    private static final int UNDO_MAX = 30;

    // ===== 剪贴板历史（仅复制文本，v0.4.5） =====
    private static final int CLIP_MAX = 20;
    private static final String PREFS_NAME = "cloudwubi";
    private static final String PREFS_CLIP = "clip_history";
    /** v0.5.34 反馈⑧：剪贴板项标记 span（长按定位删除） */
    private static class ClipTagSpan { int index; ClipTagSpan(int i) { index = i; } }
    private float lastTouchX = 0f, lastTouchY = 0f;   // v0.5.34 长按定位坐标
    private android.view.GestureDetector candFlingDetector;   // v0.5.35 反馈③：候选左右滑动翻页
    private static final String PREFS_PHRASES = "recent_phrases";  // v0.4.8 MRU 词组
    private static final String PREFS_LAST_SEL = "last_selected";  // v0.5.9 反馈⑧：上次选中字/词持久化（字频调整跨会话生效）

    private final StringBuilder composingCode = new StringBuilder();
    private List<String> candidates = new ArrayList<>();
    private TextView candidateView;
    private CloudKeyboardView keyboardView;
    private LinearLayout rootView;
    private LinearLayout toolRow;   // v0.5.0 反馈①：工具行（全选/取消↺/重做↻）
    private android.widget.TextView hideBtn;   // v0.5.4 反馈⑨：闲置 2 秒后出现的收起键盘按钮
    /** v0.5.5：闲置定时器——2 秒出现收起按钮（反馈④，INVISIBLE 占位不跳动）
     *  v0.5.8 反馈⑤：取消状态栏/备选栏自动清空（原 1 秒清空候选已移除，候选保留待用户操作） */
    private final android.os.Handler idleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            // v0.5.10 反馈③：下隐常显，不再计时显示
        }
    };
    /** v0.5.5 反馈①：密码框模式（禁用联想/剪贴板/翻译，保证可输入） */
    private boolean isPassword = false;
    /** v0.5.5 反馈⑤：符号面板来源面板（0=主键盘，1=数字面板），用于"返回"逐层回退 */
    private int prevPanel = 0;
    /** v0.5.5 反馈⑦：进入输入状态时联想基准字（光标前一字），云端回填校验用 */
    private String enterAssociateChar = "";
    private Keyboard keyboardMain;
    private Keyboard keyboardNum;
    private Keyboard keyboardSymRecent;   // v0.5.34 符号面板分类：最近
    private Keyboard keyboardSymCn;       // 中文
    private Keyboard keyboardSymEn;       // 英文
    private Keyboard keyboardSymEmoji;    // 表情
    private Keyboard keyboardSymNet;      // 网络
    private boolean chineseMode = true;
    private boolean fromChineseShift = false;   // v0.5.10 反馈①：记录 shift 是否从中文切入英文大写
    private int panelMode = 0;              // 0=主键盘 1=数字 2=符号
    private boolean clipMode = false;       // 候选条是否显示剪贴板历史
    private boolean infoPanelMode = false;  // v0.5.13 反馈①：候选条是否显示 app 信息面板
    private int candViewH = 0;              // v0.5.14 反馈⑤：备选栏固定高度（正常态单行不抖动）
    private String lastSelected = "";       // MRU 置顶
    private String lastCommittedChar = "";  // v0.4.8 最近上屏单字（触发联想）
    private String lastEnHint = "";         // v0.4.8 最近选字英文翻译提示
    private android.widget.TextView statusInfo;   // v0.5.8 状态栏：编码 + 英文翻译（左侧"云五笔"固定）
    private boolean calcAuto = false;       // v0.5.8 反馈⑥：续算去重仅用于运算符自动续接（防误伤手动输入）
    private String lastCalcInput = "";      // v0.5.35 反馈①：最近一次直接上屏的数字（运算符按下时回收为表达式起点）
    private List<String> recentPhrases = new ArrayList<>();  // v0.4.8 最近3个选中词组
    private String calcBuffer = "";         // v0.4.8 数字面板计算表达式
    private String lastCalcResult = "";     // v0.5.3 反馈②：上次计算结果（上屏后接运算符可继续计算）
    private boolean calcFormula = true;     // v0.4.8 默认带公式上屏（长按=仅结果）

    // Shift / Caps（反馈③）
    private int shiftState = 0;             // 0=小写 1=单次大写 2=锁定大写
    private long lastShiftTap = 0L;

    private ClipboardManager clipManager;
    private SharedPreferences prefs;
    private List<String> clipHistory = new ArrayList<>();
    // v0.5.36 反馈④：剪贴板长按删除 → 点"取消↺"恢复还原（防误操作）
    private String clipUndoItem = null;
    private int clipUndoIndex = -1;
    /** v0.5.14 反馈②：端侧词频缓存（最近 3 个月输入记录——上屏词→次数，联想排序权重） */
    private static final String PREFS_FREQ = "cw_freq";
    private final Map<String, Integer> freqMap = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        WubiDb.init(this);   // 加载离线词库（res/raw）
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadClipHistory();
        loadRecentPhrases();
        loadFreq();   // v0.5.14 反馈②：加载端侧词频缓存
        // v0.5.9 反馈⑧：恢复上次选中字/词（字频调整跨会话生效，重启仍记忆）
        lastSelected = prefs.getString(PREFS_LAST_SEL, "");
        try {
            clipManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipManager != null) {
                clipManager.addPrimaryClipChangedListener(clipListener);
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        try {
            if (clipManager != null) clipManager.removePrimaryClipChangedListener(clipListener);
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        candidateView = new TextView(this);
        candidateView.setTextSize(18);   // v0.5.14 反馈⑥：备选词组大字显示（透明背景默认）
        candidateView.setPadding(14, 12, 14, 12);
        // v0.5.14 反馈⑤：固定备选栏高度（单行）→ 不撑大显示范围、无画面抖动
        candViewH = Math.round(46 * getResources().getDisplayMetrics().density);
        candidateView.setMinHeight(candViewH);
        candidateView.setMaxHeight(candViewH);
        candidateView.setMaxLines(1);
        candidateView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        candidateView.setHighlightColor(0x00000000);
        // v0.5.35 反馈③：候选条左右滑动翻页（替代点击翻页）
        candFlingDetector = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float velocityX, float velocityY) {
                // v0.5.36 反馈①：所有翻页都用滑动——左右/上下滑动均翻页
                float vx = Math.abs(velocityX), vy = Math.abs(velocityY);
                if (Math.max(vx, vy) > 200) {
                    if (vx > vy) {
                        if (velocityX < 0) { nextCandidatePage(); return true; }
                        prevCandidatePage(); return true;
                    } else {
                        if (velocityY < 0) { nextCandidatePage(); return true; }
                        prevCandidatePage(); return true;
                    }
                }
                return false;
            }
        });
        // v0.5.10 反馈②：点击候选区空白（非条目/非返回）→ 关闭剪贴板回正常输入
        candidateView.setOnClickListener(v -> {
            if (clipMode) {
                clipMode = false;
                updateCandidateView();
            }
        });
        // v0.5.34 反馈⑧：长按剪贴板列表某一项 = 删除该项（通过触摸坐标定位）
        candidateView.setOnLongClickListener(v -> {
            if (clipMode) {
                try {
                    int off = candidateView.getOffsetForPosition(lastTouchX, lastTouchY);
                    ClipTagSpan[] tags = candidateView.getText() == null ? null
                            : (ClipTagSpan[]) ((android.text.Spanned) candidateView.getText()).getSpans(off, off, ClipTagSpan.class);
                    if (tags != null && tags.length > 0) {
                        removeClipItem(tags[0].index);
                        updateCandidateView();
                        return true;
                    }
                } catch (Exception ignored) { }
            }
            return false;
        });
        // v0.5.34 反馈⑧：记录触摸坐标（长按定位用）+ v0.5.35 反馈③：左右滑动翻页
        candidateView.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                lastTouchX = ev.getX();
                lastTouchY = ev.getY();
            }
            candFlingDetector.onTouchEvent(ev);
            return false;   // 不消费：让 LinkMovementMethod 处理点击/长按
        });

        keyboardMain = new Keyboard(this, R.xml.keyboard_qwerty);
        keyboardNum = new Keyboard(this, R.xml.keyboard_num);
        keyboardSymRecent = new Keyboard(this, R.xml.keyboard_sym_recent);
        keyboardSymCn = new Keyboard(this, R.xml.keyboard_sym_cn);
        keyboardSymEn = new Keyboard(this, R.xml.keyboard_sym_en);
        keyboardSymEmoji = new Keyboard(this, R.xml.keyboard_sym_emoji);
        keyboardSymNet = new Keyboard(this, R.xml.keyboard_sym_net);
        keyboardView = new CloudKeyboardView(this, null);
        keyboardView.setKeyboard(keyboardMain);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);
        keyboardView.setHapticFeedbackEnabled(false);   // v0.5.8 反馈②：去掉击键感应（震动）
        // 反馈④：键盘左右留边（截图约 4.5% 屏宽）
        int dp12 = Math.round(12 * getResources().getDisplayMetrics().density);
        int dp6 = Math.round(6 * getResources().getDisplayMetrics().density);
        keyboardView.setPadding(dp12, dp6, dp12, dp6);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // v0.5.0 反馈①：输入状态条工具行（全选 / 取消↺ / 重做↻）+ v0.5.1 反馈⑦：亖剪贴板置前
        // v0.5.8 反馈①：状态栏左侧"云五笔"固定 + 编码 + 英文翻译；右侧 全选|取消↺|重做↻|亖|下隐
        toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toolRow.setPadding(10, 3, 4, 3);
        android.widget.TextView brand = new android.widget.TextView(this);
        brand.setText("云五笔");
        brand.setTextSize(14);   // v0.5.34 反馈④：状态栏字体调大（参考截图）
        brand.setTextColor(dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT);
        // v0.5.13 反馈①：去掉 ⓘ 按钮（AlertDialog 在 IME 服务 Context 无法显示）→ 点"云五笔"文字打开 app 信息面板
        brand.setPadding(6, 6, 6, 6);
        brand.setOnClickListener(v -> showInfoPanel());
        toolRow.addView(brand);
        statusInfo = new android.widget.TextView(this);
        statusInfo.setTextSize(14);   // v0.5.34 反馈④
        statusInfo.setPadding(8, 0, 0, 0);
        statusInfo.setSingleLine(true);
        statusInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusInfo.setTextColor(dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT);
        // v0.5.9 反馈⑦：点击状态栏英文翻译 → 上屏翻译内容
        statusInfo.setOnClickListener(v -> {
            if (!lastEnHint.isEmpty()) {
                commitText(lastEnHint);
                lastEnHint = "";
                updateCandidateView();
            }
        });
        toolRow.addView(statusInfo);
        android.widget.Space spacer = new android.widget.Space(this);
        toolRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        toolRow.addView(makeToolButton("全选", v -> selectAll()));
        // v0.5.14 反馈③：工具栏加"删除"（删光标前字符/选区，与退格同功能）
        // v0.5.36 反馈③：取消/删除只用图标节省空间（✕=删除、↺=取消、↻=重做）
        toolRow.addView(makeToolButton("✕", v -> handleBackspace()));
        toolRow.addView(makeToolButton("↺", v -> doUndo()));
        toolRow.addView(makeToolButton("↻", v -> doRedo()));
        toolRow.addView(makeToolButton("亖", v -> {
            // v0.5.5 反馈①：密码框禁用剪贴板（隐私）
            if (isPassword) return;
            infoPanelMode = false;   // v0.5.13：切剪贴板时关闭信息面板
            clipMode = true;
            updateCandidateView();
        }));
        // v0.5.10 反馈③：下隐功能键常显（取消闲置计时），图标 🔽
        hideBtn = makeToolButton("🔽", v -> {
            try { requestHideSelf(0); } catch (Exception ignored) { }
        });
        hideBtn.setVisibility(android.view.View.VISIBLE);
        toolRow.addView(hideBtn);
        // v0.5.8 反馈①：第一行状态栏（云五笔|编码|翻译 | 工具），第二行备选栏，第三行键盘
        root.addView(toolRow);
        root.addView(candidateView);
        root.addView(keyboardView);
        rootView = root;
        // v0.5.4 反馈③：回车键长按 → 强制换行（单行/多行均生效）
        keyboardView.setOnLongPressListener(key -> {
            if (key != null && key.codes != null && key.codes.length > 0 && key.codes[0] == KB_ENTER) {
                commitText("\n");
            }
        });
        resetIdleTimers();
        applyTheme();   // v0.4.9 反馈③：FLAT + 跟随系统深浅色
        applyLangLabels();
        applyLetterCase();   // 中文模式默认大写显示
        updateCandidateView();
        return root;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyTheme();   // v0.4.9 反馈③：系统切换浅/深色时实时跟随
    }

    /** v0.5.0 反馈①：工具行按钮（全选/取消↺/重做↻） */
    private TextView makeToolButton(String text, View.OnClickListener listener) {        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);   // v0.5.34 反馈④：工具栏字体调大
        tv.setPadding(12, 4, 12, 4);   // v0.5.14 反馈③⑤：6 按钮 + 固定行高不抖动（padding 压缩）
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setOnClickListener(listener);
        return tv;
    }

    /** v0.5.13 反馈①：app 信息面板（IME 内嵌，候选条区域渲染，点任意键关闭）
     *  原因：AlertDialog 在 InputMethodService Context 下被系统拦截无法显示（v0.5.11 弹窗未出现的根因） */
    private void showInfoPanel() {
        // v0.5.28 反馈①：点一次显示 app 信息，再点消失（toggle）
        infoPanelMode = !infoPanelMode;
        if (!infoPanelMode) {
            candidateView.setSingleLine(true);
            candidateView.setMaxLines(1);
            candidateView.setMinHeight(candViewH);
            candidateView.setMaxHeight(candViewH);
        }
        clipMode = false;
        updateCandidateView();
    }

    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.5.28";
        }
    }

    private void renderInfoPanel() {
        int c = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        // v0.5.28 反馈①：版本号前去掉"云五笔"三个字，动态读安装包真实版本号（不再硬编码）
        sb.append("v").append(currentVersion());
        sb.append("  开源：github.com/zsdili  微信：175571");
        if (cloudCatCount > 0) {
            sb.append("\n云端词库：本地 2500 词 + 云端词组 6.2 万 + 分类 ")
              .append(String.valueOf(cloudCatCount))
              .append(" 类 ").append(String.valueOf(cloudCatWords)).append(" 词");
        }
        sb.setSpan(new ForegroundColorSpan(c), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        candidateView.setSingleLine(false);
        candidateView.setLineSpacing(0f, 1.25f);
        candidateView.setTextSize(16f);   // v0.5.34 反馈④：备选栏字体调大（参考截图，用户要求字大）
        candidateView.setText(sb);
    }

    /** v0.5.0 反馈①：全选当前文本框内容 */
    private void selectAll() {        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            CharSequence b = ic.getTextBeforeCursor(2000, 0);
            CharSequence a = ic.getTextAfterCursor(2000, 0);
            int len = (b == null ? 0 : b.length()) + (a == null ? 0 : a.length());
            ic.setSelection(0, len);
        } catch (Exception ignored) { }
    }

    /** v0.4.9 反馈③：FLAT 风格 + 深浅色主题（键帽/键盘底/候选条/文字全部跟随系统外观） */
    private void applyTheme() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        theme = dark ? THEME_DARK : THEME_LIGHT;
        if (keyboardView == null || candidateView == null) return;
        // 键帽：v0.4.9 纯色 FLAT 全自绘（无立体/渐变），深浅两套实时切换
        keyboardView.applyTheme(dark);
        keyboardView.setBackgroundColor(dark ? THEME_DARK_KB_BG : THEME_LIGHT_KB_BG);
        keyboardView.setHintColor(dark ? THEME_DARK_HINT : THEME_LIGHT_HINT);
        // 候选条
        candidateView.setBackgroundColor(dark ? THEME_DARK_CAND_BG : THEME_LIGHT_CAND_BG);
        candidateView.setTextColor(dark ? THEME_DARK_TEXT : THEME_LIGHT_TEXT);
        // 工具行（v0.5.0 反馈①）
        if (toolRow != null) {
            int tc = dark ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
            for (int i = 0; i < toolRow.getChildCount(); i++) {
                View v = toolRow.getChildAt(i);
                if (v instanceof TextView) ((TextView) v).setTextColor(tc);
            }
        }
        if (rootView != null) rootView.setBackgroundColor(dark ? THEME_DARK_KB_BG : THEME_LIGHT_KB_BG);
    }

    private android.graphics.drawable.Drawable getDrawableCompat(int resId) {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            return getResources().getDrawable(resId, getTheme());
        }
        return getResources().getDrawable(resId);
    }

    // ===== 生命周期 =====

    @Override
    public void onFinishInput() {
        resetComposing();
        // v0.5.17 反馈⑤（举一反三：窗口生命周期完整感知）：离开输入触点（焦点转移/输入结束）→
        //   主动收起输入界面，符合"科学、合理"的输入法基础规范
        hideWindow();
        super.onFinishInput();
    }

    @Override
    public void onStartInput(android.view.inputmethod.EditorInfo attribute, boolean restarting) {
        // v0.5.5 反馈①：标准会话入口（密码框/WebView 等必须重写，保证输入可用）
        super.onStartInput(attribute, restarting);
    }

    public void onStartInputView(EditorInfo info, boolean restarting) {
        resetComposing();
        panelMode = 0;
        clipMode = false;
        prevPanel = 0;
        keyboardView.setKeyboard(keyboardMain);
        applyLetterCase();
        // v0.5.3 反馈①：新输入会话重置"最近上屏"记录
        committedLast = "";
        // v0.5.5 反馈①：密码框检测（密码/网络密码）——禁联想/剪贴板/翻译，保证可输入
        isPassword = (info.inputType & android.text.InputType.TYPE_MASK_VARIATION)
                == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                || (info.inputType & android.text.InputType.TYPE_MASK_VARIATION)
                == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        // v0.5.5 反馈④⑧：新会话隐藏收起按钮（INVISIBLE 占位）+ 重启双闲置计时
        if (hideBtn != null) hideBtn.setVisibility(android.view.View.INVISIBLE);
        resetIdleTimers();
        // v0.5.5 反馈⑦：进入输入状态时，识别光标前一字进行联想（不自动上屏）
        // v0.5.20（用户指令）：联想已去除——不再做"进入输入状态时光标前字联想"
        enterAssociateChar = "";
        super.onStartInputView(info, restarting);
    }

    /** v0.5.5 反馈⑦：进入输入状态/光标前字联想（本地 MRU + 含字词组 + 云端前缀） */
    private void showAssociateForChar(String ch) {
        candidates.clear();
        List<String> merged = new ArrayList<>();
        for (String p : recentPhrases) {
            if (p.startsWith(ch) && !merged.contains(p)) merged.add(p);
        }
        List<String> byChar = WubiDb.queryByChar(ch);
        if (byChar != null) {
            for (String p : byChar) {
                if (!merged.contains(p)) merged.add(p);
                if (merged.size() >= 12) break;
            }
        }
        pinyinInsertPos = merged.size();   // v0.5.36 反馈⑨：拼音候选插到本地五笔候选之后
        candidates.addAll(merged);
        candPage = 0;
        updateCandidateView();
        if (GATEWAY_READY && !merged.isEmpty()) queryAssociateAsync(ch, ch);
    }

    private void resetComposing() {
        composingCode.setLength(0);
        candidates.clear();
        clipMode = false;
        lastCommittedChar = "";
        lastCommittedText = "";
        lastEnHint = "";
        associateActive = false;
        candPage = 0;
        updateCandidateView();
    }

    // ===== 剪贴板历史（仅复制触发） =====

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> {
        try {
            ClipData cd = clipManager.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence t = cd.getItemAt(0).coerceToText(CloudWubiIME.this);
                if (t != null && t.length() > 0 && t.length() <= 5000) {
                    addClipHistory(t.toString());
                    // v0.5.34 反馈③：复制的内容在备选栏单独只显示一次（点选上屏/点其他部位消失）
                    clipMode = true;
                    updateCandidateView();
                }
            }
        } catch (Exception ignored) { }
    };

    /** v0.5.34 反馈⑧：删除剪贴板历史某一项（长按/点✕） */
    private void removeClipItem(int index) {
        if (index < 0 || index >= clipHistory.size()) return;
        clipUndoItem = clipHistory.get(index);   // v0.5.36 反馈④：记录待撤销项
        clipUndoIndex = index;
        clipHistory.remove(index);
        saveClipHistory();
    }

    private void addClipHistory(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String norm = text.replace('\u0001', ' ');
        clipHistory.remove(norm);
        clipHistory.add(0, norm);
        while (clipHistory.size() > CLIP_MAX) clipHistory.remove(clipHistory.size() - 1);
        saveClipHistory();
    }

    private void saveClipHistory() {
        StringBuilder sb = new StringBuilder();
        for (String s : clipHistory) sb.append(s).append('\u0001');
        prefs.edit().putString(PREFS_CLIP, sb.toString()).apply();
    }

    private void loadClipHistory() {
        clipHistory.clear();
        String raw = prefs.getString(PREFS_CLIP, "");
        if (!raw.isEmpty()) {
            String[] arr = raw.split("\u0001", -1);
            for (String s : arr) if (!s.isEmpty()) clipHistory.add(s);
        }
    }

    // ===== v0.4.8 最近选中词组（MRU，最多 3 个，用于字后联想置顶） =====

    private void loadRecentPhrases() {
        recentPhrases.clear();
        Set<String> set = prefs.getStringSet(PREFS_PHRASES, new HashSet<>());
        List<String> tmp = new ArrayList<>(set);
        for (int i = tmp.size() - 1; i >= 0; i--) recentPhrases.add(tmp.get(i));
        while (recentPhrases.size() > 3) recentPhrases.remove(recentPhrases.size() - 1);
    }

    private void rememberPhrase(String phrase) {
        if (phrase == null || phrase.length() < 2) return;
        recentPhrases.remove(phrase);
        recentPhrases.add(0, phrase);
        while (recentPhrases.size() > 3) recentPhrases.remove(recentPhrases.size() - 1);
        prefs.edit().putStringSet(PREFS_PHRASES, new HashSet<>(recentPhrases)).apply();
    }

    // ===== v0.4.8 数字面板四则计算（先乘除后加减，左结合） =====

    private static double calcEval(String expr) {
        expr = expr.replace('×', '*').replace('÷', '/');
        List<Double> nums = new ArrayList<>();
        List<Character> ops = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '+' || c == '-' || c == '*' || c == '/') {
                if (cur.length() == 0) return Double.NaN;
                nums.add(Double.parseDouble(cur.toString()));
                cur.setLength(0);
                ops.add(c);
            } else if (c >= '0' && c <= '9' || c == '.') {
                cur.append(c);
            } else {
                return Double.NaN;
            }
        }
        if (cur.length() == 0) return Double.NaN;
        nums.add(Double.parseDouble(cur.toString()));
        // 先乘除
        for (int i = 0; i < ops.size(); i++) {
            char op = ops.get(i);
            if (op == '*' || op == '/') {
                double a = nums.get(i), b = nums.get(i + 1);
                double r = (op == '*') ? a * b : (b == 0 ? Double.NaN : a / b);
                if (Double.isNaN(r)) return Double.NaN;
                nums.set(i, r);
                nums.remove(i + 1);
                ops.remove(i);
                i--;
            }
        }
        // 再加减（左结合）
        double acc = nums.get(0);
        for (int i = 0; i < ops.size(); i++) {
            char op = ops.get(i);
            double b = nums.get(i + 1);
            acc = (op == '+') ? acc + b : acc - b;
        }
        return acc;
    }

    private static String fmtResult(double v) {
        if (Double.isNaN(v)) return "错误";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 1e8) / 1e8);
    }

    // ===== 键盘文字：中文「中」/ 英文「EN」及功能键英文（反馈②） =====

    private void applyLangLabels() {
        if (keyboardMain == null) return;
        for (Keyboard.Key k : keyboardMain.getKeys()) {
            int c = k.codes[0];
            if (c == KEY_LANG) {
                k.label = chineseMode ? "中" : "en";   // v0.5.27 反馈④：英文切换显示小写 en
            } else if (c == KEY_SYM_IN) {
                k.label = chineseMode ? "符" : "SYM";
            } else if (c == KEY_PUNCT_BANG) {
                // v0.5.3 反馈⑤：上下两行（上行！下行，）——CloudKeyboardView 识别 \n 分行绘制
                k.label = chineseMode ? "！\n，" : "!\n,";
            } else if (c == KEY_PUNCT_QM) {
                k.label = chineseMode ? "？\n。" : "?\n.";
            } else if (c == KEY_MIC) {
                k.label = chineseMode ? "空格" : "space";   // v0.4.8 反馈⑤：空格键
            }
        }
        keyboardView.invalidateAllKeys();
    }

    // ===== 字母大小写显示（反馈③：不依赖 shiftLabel，直接切换 label） =====

    private void applyLetterCase() {
        if (keyboardMain == null) return;
        boolean upper = chineseMode || shiftState > 0;
        for (Keyboard.Key k : keyboardMain.getKeys()) {
            int c = k.codes[0];
            if (c >= 'a' && c <= 'z') {
                k.label = upper ? String.valueOf(Character.toUpperCase((char) c))
                                : String.valueOf((char) c);
            }
        }
        keyboardView.invalidateAllKeys();
    }

    // ===== Shift / Caps 逻辑（反馈③） =====

    private void handleShift() {
        // v0.5.10 反馈①：单击切换"持续大写"模式（Caps 锁定式，非单次复位）
        // 中文 → 英文大写；从中文切来的英文大写 → 回中文；纯英文 → 大写/小写切换
        if (chineseMode) {
            chineseMode = false;
            composingCode.setLength(0);
            candidates.clear();
            shiftState = 2;                 // 英文大写模式
            fromChineseShift = true;        // 记录"从中文切来"，再单击回中文
            lastCommittedChar = "";
            lastCommittedText = "";
            lastEnHint = "";
            associateActive = false;
            candPage = 0;
        } else if (fromChineseShift) {
            chineseMode = true;             // 恢复中文输入
            shiftState = 0;
            fromChineseShift = false;
        } else if (shiftState == 0) {
            shiftState = 2;                 // 英文小写 → 持续大写
        } else {
            shiftState = 0;                 // 英文大写 → 恢复小写
        }
        lastShiftTap = System.currentTimeMillis();
        applyLetterCase();
        applyLangLabels();
        updateCandidateView();
    }

    // ===== KeyboardView.OnKeyboardActionListener =====

    /** v0.5.4 反馈⑨ + v0.5.5 反馈④⑧：重置双闲置计时器（每次按键触发；1 秒清空备选栏、2 秒显示收起按钮） */
    private void resetIdleTimers() {
        // v0.5.10 反馈③：下隐常显，取消闲置计时
        idleHandler.removeCallbacks(idleRunnable);
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        resetIdleTimers();   // v0.5.4 反馈⑨ + v0.5.5 反馈⑧：任何按键重置双闲置计时
        // v0.5.13 反馈①：信息面板点任意键关闭
        if (infoPanelMode) {
            infoPanelMode = false;
            updateCandidateView();
        }
        // v0.5.9 反馈⑩：剪贴板界面有键盘点击 → 自动收回剪贴板到正常输入界面
        if (clipMode) {
            clipMode = false;
            updateCandidateView();
        }
        // v0.5.17 反馈①（举一反三：InputType 完整感知）：密码框直通模式——
        //   字母/数字/空格/回车/运算符直接上屏，不进编码缓冲/候选/联想（密码可见性 + 输入体验根本保障）
        //   v0.5.21 修复：Shift/面板切换/退格/中英/标点等控制键不再被吞——只对已直通键 return，
        //   其余继续走正常逻辑（否则密码框无法切换大小写、无法进数字/符号面板）
        if (isPassword) {
            if (primaryCode >= 'a' && primaryCode <= 'z') {
                boolean upper = shiftState > 0;
                commitText(String.valueOf(upper ? Character.toUpperCase((char) primaryCode)
                                                 : (char) primaryCode));
                return;
            } else if (primaryCode >= '0' && primaryCode <= '9') {
                commitText(String.valueOf((char) primaryCode));
                return;
            } else if (primaryCode == KEY_SPACE || primaryCode == KEY_MIC) {
                commitText(" ");
                return;
            } else if (primaryCode == KB_ENTER) {
                sendDefaultEditorAction(true);   // 执行输入框的"完成/搜索/前往"等动作
                return;
            } else if (primaryCode == KEY_CALC_DIV) {
                commitText("÷"); return;
            } else if (primaryCode == KEY_CALC_MUL) {
                commitText("×"); return;
            } else if (primaryCode == KEY_CALC_EQ) {
                commitText("="); return;
            } else if (primaryCode == 43 || primaryCode == 45 || primaryCode == 42) {
                commitText(String.valueOf((char) primaryCode)); return;
            } else if (primaryCode >= 32 && primaryCode <= 126) {
                // v0.5.24 修复③：密码框 ASCII 可见字符兜底直通（特殊字符在密码模式直接上屏）
                commitText(String.valueOf((char) primaryCode)); return;
            }
            // 其余键（Shift/123/符号/退格/中英/返回）→ 不拦截，走正常逻辑（面板切换/大小写/删除可用）
        }
        // 字母键
        if (primaryCode >= 'a' && primaryCode <= 'z') {
            if (chineseMode) {
                appendCode((char) primaryCode);
            } else {
                // v0.5.0 反馈⑤：英文输入进编码 → 自动补全候选（@邮箱、ht→https:// 等）
                appendEnglishCode((char) primaryCode);
            }
            return;
        }
        // 数字（v0.5.35 反馈①：纯数字直接上屏——只有表达式已含运算符时才进缓冲，根治"打5出1.5=5 2.5"）
        if (primaryCode >= '0' && primaryCode <= '9') {
            if (panelMode == 1 && hasCalcOp(calcBuffer)) {
                calcBuffer += (char) primaryCode;
                updateCandidateView();
            } else {
                commitText(String.valueOf((char) primaryCode));
                lastCalcInput = String.valueOf((char) primaryCode);   // 回收点：运算符按下时作为表达式起点
            }
            return;
        }
        switch (primaryCode) {
            case KEY_123:
                // v0.5.27 反馈③：数字面板↔主键盘切换时，未上屏的表达式先带式上屏（防计算结果丢失/上不了屏）
                if (panelMode == 1 && !calcBuffer.isEmpty()) commitCalc(true);
                panelMode = 1;
                calcBuffer = "";
                keyboardView.setKeyboard(keyboardNum);
                updateCandidateView();
                return;
            case KEY_SYM_IN:   // v0.5.34 符号面板分类式：进入"最近"分类（参考用户截图）
                if (panelMode < 2 || panelMode > 6) {
                    prevPanel = panelMode;
                }
                panelMode = 2;
                keyboardView.setKeyboard(keyboardSymRecent);
                return;
            case KEY_SYM_RECENT:   // v0.5.34 分类切换（左栏）
                panelMode = 2;
                keyboardView.setKeyboard(keyboardSymRecent);
                return;
            case KEY_SYM_CN:
                panelMode = 3;
                keyboardView.setKeyboard(keyboardSymCn);
                return;
            case KEY_SYM_EN:
                panelMode = 4;
                keyboardView.setKeyboard(keyboardSymEn);
                return;
            case KEY_SYM_EMOJI:
                panelMode = 5;
                keyboardView.setKeyboard(keyboardSymEmoji);
                return;
            case KEY_SYM_NET:
                panelMode = 6;
                keyboardView.setKeyboard(keyboardSymNet);
                return;
            case KEY_SYM_LOCK:   // 🔒 面板锁定（占位：不自动收起）
                return;
            case KEY_SYM_UP:     // ⌃ 无多页翻页（占位）
            case KEY_SYM_DOWN:   // ⌄
                return;
            case KEY_NET_COM: commitText(".com"); return;
            case KEY_NET_NET: commitText(".net"); return;
            case KEY_NET_CN: commitText(".cn"); return;
            case KEY_NET_WWW: commitText("www."); return;
            case KEY_NET_HTTP: commitText("http://"); return;
            case KEY_NET_HTTPS: commitText("https://"); return;
            case KEY_NET_SLASH2: commitText("//"); return;
            case KEY_NET_COMSLASH: commitText(".com/"); return;
            case KEY_SYMBOL:   // v0.5.34 符号面板返回：分类 → 来源面板/主键盘；数字面板有表达式先带式上屏
                if (panelMode >= 2 && panelMode <= 6) {
                    if (prevPanel == 1) {
                        panelMode = 1;
                        keyboardView.setKeyboard(keyboardNum);
                    } else {
                        panelMode = 0;
                        keyboardView.setKeyboard(keyboardMain);
                    }
                    updateCandidateView();
                    return;
                }
                if (panelMode == 1 && !calcBuffer.isEmpty()) commitCalc(true);
                panelMode = 0;
                calcBuffer = "";
                keyboardView.setKeyboard(keyboardMain);
                updateCandidateView();
                return;
            case KEY_LANG:
                if (panelMode != 0) {
                    panelMode = 0;
                    calcBuffer = "";
                    keyboardView.setKeyboard(keyboardMain);
                } else {
                    toggleLang();
                }
                return;
            case KEY_SHIFT:
                handleShift();
                return;
            case KEY_PUNCT_BANG:   // v0.5.1：点击默认"，"/","（英文态），上滑"！"
                commitText(chineseMode ? "，" : ",");
                return;
            case KEY_PUNCT_QM:     // v0.5.1：点击默认"。" / "."（英文态），上滑"？"
                commitText(chineseMode ? "。" : ".");
                return;
            case KEY_MIC:          // v0.5.4 反馈⑦：空格键——备选栏有候选则上屏首选，否则输出空格
            case KEY_SPACE:
                commitSpaceOrFirst();
                return;
            case KEY_CALC_EQ:      // = 计算上屏（旧面板键，保留兼容）
                commitCalc(true);
                return;
            case KEY_CALC_DIV:     // ÷
                if (panelMode == 1) { calcAppendOp("÷"); } else commitText("÷");
                return;
            case KEY_CALC_MUL:     // ×
                if (panelMode == 1) { calcAppendOp("×"); } else commitText("×");
                return;
            case 43:               // +
                if (panelMode == 1) { calcAppendOp("+"); } else commitText("+");
                return;
            case 45:               // -
                if (panelMode == 1) { calcAppendOp("-"); } else commitText("-");
                return;
            case 42:               // *（数字面板计算）
                if (panelMode == 1) { calcAppendOp("*"); } else commitText("*");
                return;
            case 47:               // /（数字面板计算）
                if (panelMode == 1) { calcAppendOp("/"); } else commitText("/");
                return;
            case 64:               // @（数字面板邮箱直上屏）
                commitText("@");
                return;
            case KEY_UNDO:         // v0.4.9 取消↺
                doUndo();
                return;
            case KEY_REDO:         // v0.4.9 重做↻
                doRedo();
                return;
            case KEY_SEARCH:       // v0.5.0 搜索键（数字面板）：有表达式→带式上屏；否则执行搜索动作
                if (panelMode == 1 && !calcBuffer.isEmpty()) {
                    commitCalc(true);
                    return;
                }
                InputConnection sic = getCurrentInputConnection();
                if (sic != null) {
                    try { sic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); } catch (Exception ignored) { }
                }
                return;
            case KB_DELETE:
            case KeyEvent.KEYCODE_DEL:
                handleBackspace();
                return;
            case KB_ENTER:
            case KeyEvent.KEYCODE_ENTER:
                commitFirstCandidate();
                return;
            case 44:
                if (panelMode == 1) { calcBuffer += ","; updateCandidateView(); return; }
                commitText(chineseMode ? "，" : ",");
                return;
            case 46:
                if (panelMode == 1) { calcBuffer += "."; updateCandidateView(); return; }
                commitText(chineseMode ? "。" : ".");
                return;
            case 0xFF01:   // v0.5.17 修复回归：！，键上滑 → ！（CloudKeyboardView.swipeSymbol 直达）
                commitText("！");
                return;
            case 0xFF1F:   // v0.5.17 修复回归：？。键上滑 → ？
                commitText("？");
                return;
            default:
                // v0.5.24 修复②：符号键兜底——symbolToText 未覆盖的 ASCII 可见字符直接上屏
                //   （91[]/93]/123{/125}/35#/37%/94^/61= 等特殊字符，密码框与常规模式通用）
                String s = symbolToText(primaryCode);
                if (s != null) {
                    commitText(s);
                } else if (primaryCode >= 32 && primaryCode <= 126) {
                    commitText(String.valueOf((char) primaryCode));
                }
        }
    }

    private int lastPressCode = 0;   // v0.5.1 上滑输出（！/？）

    @Override
    public void onPress(int primaryCode) {
        lastPressCode = primaryCode;
    }

    @Override
    public void onRelease(int primaryCode) { }

    @Override
    public void onText(CharSequence text) { }

    @Override
    public void swipeLeft() { }

    @Override
    public void swipeRight() { }

    @Override
    public void swipeDown() { }

    @Override
    public void swipeUp() {
        // v0.5.1 反馈④：！，键上滑→！，？。键上滑→？
        if (lastPressCode == KEY_PUNCT_BANG) {
            commitText("！");
            lastPressCode = 0;
        } else if (lastPressCode == KEY_PUNCT_QM) {
            commitText("？");
            lastPressCode = 0;
        }
    }

    // ===== 物理键盘支持 =====

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int c = event.getUnicodeChar();
        if (c >= 'a' && c <= 'y' && chineseMode) {
            appendCode((char) c);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                handleBackspace();
                return true;
            case KeyEvent.KEYCODE_ENTER:
                commitFirstCandidate();
                return true;
            case KeyEvent.KEYCODE_SPACE:
                commitFirstCandidate();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ===== 核心逻辑 =====

    private void toggleLang() {
        fromChineseShift = false;   // v0.5.10：手动中英键切换非 shift 切入
        chineseMode = !chineseMode;
        if (!chineseMode) {
            composingCode.setLength(0);
            candidates.clear();
            shiftState = 0;
            lastCommittedChar = "";
            lastCommittedText = "";
            lastEnHint = "";
            associateActive = false;
            candPage = 0;
        }
        applyLetterCase();
        applyLangLabels();
        updateCandidateView();
    }

    private void appendCode(char c) {
        // v0.5.35 反馈⑥：放宽到 8 码支持拼音全拼混打（五笔查询自动截前4，>4 码走拼音分支）
        if (composingCode.length() >= 8) return;
        associateActive = false;   // v0.4.9 开始新编码 → 联想链断开
        candPage = 0;
        composingCode.append(c);
        queryCandidates();
    }

    /** v0.5.0 反馈⑤：英文输入进编码（放宽到 24 字符，触发补全候选）
     *  v0.5.16 反馈②：shift 大写生效——shiftState>0（大写锁定/中文切英文大写）时存大写，否则小写 */
    private void appendEnglishCode(char c) {
        if (composingCode.length() >= 24) return;
        candPage = 0;
        boolean upper = shiftState > 0;
        composingCode.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
        queryCandidates();
    }

    private void handleBackspace() {
        // v0.4.8：数字面板优先删计算表达式
        if (panelMode == 1 && !calcBuffer.isEmpty()) {
            calcBuffer = calcBuffer.substring(0, calcBuffer.length() - 1);
            updateCandidateView();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        // v0.5.11 反馈⑥：文本框有选区（全选/部分选中）→ 优先删除选区（而非备选栏编码）
        if (ic != null) {
            try {
                CharSequence sel = ic.getSelectedText(0);
                if (sel != null && sel.length() > 0) {
                    pushUndo();
                    ic.commitText("", 0);
                    return;
                }
            } catch (Exception ignored) { }
        }
        if (composingCode.length() > 0) {
            composingCode.deleteCharAt(composingCode.length() - 1);
            queryCandidates();
        } else {
            if (ic == null) return;
            // v0.4.8 反馈②：全选/部分选中时删除整个选区（此分支现仅兜底，选区已在上面优先处理）
            // v0.5.3 反馈①：删除刚上屏的字后允许重新输入（清空过滤键）
            String prev = getCursorPrevChar();
            if (!prev.isEmpty() && prev.equals(committedLast)) committedLast = "";
            pushUndo();   // v0.4.9 取消↺ 可恢复删除
            ic.deleteSurroundingText(1, 0);
        }
    }

    /** 上屏（v0.4.5：不写入剪贴板历史；v0.4.9：自动记录 undo 快照） */
    private void commitText(String s) {
        pushUndo();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            // v0.5.5 反馈①：先结束组合态再提交（密码框/WebView 兼容，避免吞字）
            try { ic.finishComposingText(); } catch (Exception ignored) { }
            ic.commitText(s, 1);
        }
    }

    // ===== v0.5.14 反馈②：端侧词频缓存（最近 3 个月输入记录，联想排序权重） =====

    private void loadFreq() {
        String j = prefs.getString(PREFS_FREQ, "");
        if (j.isEmpty()) return;
        try {
            for (String kv : j.split(",")) {
                int i = kv.indexOf(':');
                if (i > 0) freqMap.put(kv.substring(0, i), Integer.parseInt(kv.substring(i + 1)));
            }
        } catch (Exception ignored) { }
    }

    private void saveFreq() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : freqMap.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        prefs.edit().putString(PREFS_FREQ, sb.toString()).apply();
    }

    private void bumpFreq(String text) {
        if (text == null || text.isEmpty()) return;
        freqMap.put(text, freqOf(text) + 1);
        saveFreq();
    }

    private int freqOf(String w) {
        Integer n = freqMap.get(w);
        return n == null ? 0 : n;
    }

    // ===== v0.4.9 取消↺ / 重做↻（编辑快照栈，双向 30 层） =====

    /** 记录当前文档快照（光标前 2000 字 + 分隔符 + 光标后 2000 字） */
    private String snapshotDoc() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        String b = "", a = "";
        try {
            CharSequence cb = ic.getTextBeforeCursor(2000, 0);
            CharSequence ca = ic.getTextAfterCursor(2000, 0);
            b = cb == null ? "" : cb.toString();
            a = ca == null ? "" : ca.toString();
        } catch (Exception ignored) { }
        return b + "\u0002" + a;
    }

    /** 任意文档变更前调用：当前快照入 undo 栈，清空 redo 栈 */
    private void pushUndo() {
        String snap = snapshotDoc();
        if (snap.isEmpty() || snap.equals("\u0002")) return;
        undoStack.push(snap);
        while (undoStack.size() > UNDO_MAX) undoStack.removeLast();
        redoStack.clear();
    }

    private void doUndo() {
        // v0.5.36 反馈④：剪贴板长按删除 → 点取消↺恢复还原
        if (clipUndoItem != null) {
            clipHistory.add(Math.min(clipUndoIndex, clipHistory.size()), clipUndoItem);
            saveClipHistory();
            clipUndoItem = null;
            clipUndoIndex = -1;
            if (clipMode) updateCandidateView();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || undoStack.isEmpty()) return;
        redoStack.push(snapshotDoc());
        while (redoStack.size() > UNDO_MAX) redoStack.removeLast();
        restoreDoc(undoStack.pop(), ic);
    }

    private void doRedo() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || redoStack.isEmpty()) return;
        undoStack.push(snapshotDoc());
        while (undoStack.size() > UNDO_MAX) undoStack.removeLast();
        restoreDoc(redoStack.pop(), ic);
    }

    /** 用快照整体恢复文档（先清光标两侧文本，再写入快照并定位光标） */
    private void restoreDoc(String snap, InputConnection ic) {
        int sep = snap.indexOf('\u0002');
        String before = sep < 0 ? "" : snap.substring(0, sep);
        String after = sep < 0 ? "" : snap.substring(sep + 1);
        String curB = "", curA = "";
        try {
            CharSequence cb = ic.getTextBeforeCursor(2000, 0);
            CharSequence ca = ic.getTextAfterCursor(2000, 0);
            curB = cb == null ? "" : cb.toString();
            curA = ca == null ? "" : ca.toString();
        } catch (Exception ignored) { }
        try {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(curB.length(), curA.length());
            ic.commitText(before + after, 1);
            ic.setSelection(before.length(), before.length());
            ic.endBatchEdit();
        } catch (Exception ignored) { }
    }

    /** 码点 -> 上屏文本（中文标点映射全角） */
    private String symbolToText(int code) {
        switch (code) {
            case 44: return "，";
            case 46: return "。";
            case 63: return "？";
            case 33: return "！";
            case 58: return "：";
            case 59: return "；";
            case 40: return "（";
            case 41: return "）";
            case 183: return "·";
            case 8226: return "·";
            case 34: return "\"";
            case 39: return "'";
            case 45: return "-";
            case 47: return "/";
            case 64: return "@";
            case 35: return "#";
            case 95: return "_";
            case 38: return "&";
            case 42: return "*";
            case 37: return "%";
            case 43: return "+";
            case 61: return "=";
            case 91: return "[";
            case 93: return "]";
            default:
                // v0.5.34 支持 emoji 码点（>0xFFFF 用 toChars 双字符）
                if (code >= 33 && code <= 0x10FFFF) {
                    try {
                        return String.valueOf(Character.toChars(code));
                    } catch (Exception e) {
                        return null;
                    }
                }
                return null;
        }
    }

    /** 候选查询：v0.5.0 反馈②排序（①上次上屏+热点词组 ②传统五笔 ③预测/云端）+ 反馈⑤英文补全 */
    private void queryCandidates() {
        candidates.clear();
        String code = composingCode.toString();
        if (code.isEmpty()) {
            updateCandidateView();
            return;
        }
        // v0.5.0 反馈⑤：英文输入态 → 补全候选（@邮箱 / ht→https:// / .com 等）
        if (!chineseMode) {
            checkEnglishCompletion(code);
            if (candidates.isEmpty()) candidates.add(code);
            updateCandidateView();
            return;
        }
        // v0.5.35 反馈⑥：超 4 码 = 拼音全拼模式——五笔仅用前 4 码查词组，拼音候选云端异步置前
        if (code.length() > 4) {
            String wb = code.substring(0, 4);
            List<String> pri = WubiDb.query(wb);
            if (pri != null) {
                for (String c2 : pri) {
                    if (c2.length() < 2) continue;
                    if (!isJustCommitted(c2) && !candidates.contains(c2)) candidates.add(c2);
                }
            }
            updateCandidateView();
            queryPinyin(code, true);
            return;
        }
        List<String> merged = new ArrayList<>();
        // v0.5.3 反馈①：只滤"最近一次上屏的同一字/词"（避免重复显示）；MRU 词组保留置顶
        // v0.5.4 反馈②：上次选中的字/词置顶须"编码匹配当前输入"（避免错位霸榜挡住四码词组）
        // v0.5.8 反馈③⑧：排序硬规则——1 码一级简码字最前、4 码词组最前；2/3 码 MRU 置顶
        // v0.5.9 反馈⑧：MRU 精确匹配（编码==code）置顶最优先（字频调整：上次上屏的字/词永远第一位）
        // v0.5.17 反馈④（举一反三）：MRU 置顶不受 isJustCommitted 限制——重打同码时"最近打过的字"必须置顶
        if (!lastSelected.isEmpty()) {
            String lc = lastSelected.length() >= 2 ? WubiDb.phraseCode(lastSelected) : WubiDb.singleCode(lastSelected);
            if (lc != null && lc.equals(code) && !merged.contains(lastSelected)) merged.add(lastSelected);
        }
        for (String p : recentPhrases) {
            String pc = WubiDb.phraseCode(p);
            if (pc != null && pc.equals(code) && !merged.contains(p)) merged.add(p);
        }
        if (code.length() == 1 || code.length() == 3 || code.length() == 4) {
            List<String> pri = WubiDb.query(code);
            if (pri != null) {
                for (String c : pri) {
                    // v0.5.37 反馈②：4 码单字不再过滤（WubiDb.query(4) 顺序=词组先单字后，词组仍优先）
                    if (!isJustCommitted(c) && !merged.contains(c)) merged.add(c);
                }
            }
        }
        // ① MRU：上次选中的字/词（编码前缀匹配）+ 最近上屏词组（编码匹配）
        if (!lastSelected.isEmpty()) {
            String lc = lastSelected.length() >= 2 ? WubiDb.phraseCode(lastSelected) : WubiDb.singleCode(lastSelected);
            if (lc != null && lc.startsWith(code) && !lc.equals(code) && !merged.contains(lastSelected)) merged.add(lastSelected);
        }
        for (String p : recentPhrases) {
            String pc = WubiDb.phraseCode(p);
            if (pc != null && pc.startsWith(code) && !pc.equals(code) && !merged.contains(p)) merged.add(p);
        }
        // v0.5.23 动态拼词（钟总核心思路）：基础库+86规则 → 4 码词组无限，
        //   保障词置顶 + 2+2/1+1+2/1+1+1+1 动态组合（词组在前、单字殿后）
        if (code.length() == 4) {
            List<String> dyn = WubiDb.buildDynamicWords(code);
            if (dyn != null) {
                for (String c : dyn) {
                    if (isJustCommitted(c)) continue;
                    if (!merged.contains(c)) merged.add(c);
                }
            }
        }
        // ② 第二位：传统五笔（高频字/字根/一至四码简码词组，优先照顾老用户习惯）
        List<String> local = WubiDb.query(code);
        if (local != null) {
            for (String c : local) {
                if (isJustCommitted(c)) continue;
                if ((code.length() == 1 || code.length() == 4) && merged.contains(c)) continue;   // 已在优先段
                if (!merged.contains(c)) merged.add(c);
            }
        }
        // ③ 3码预测第4码高频词组
        if (code.length() == 3) {
            List<String> predict = WubiDb.queryPredict(code);
            if (predict != null) {
                for (String p : predict) {
                    if (isJustCommitted(p)) continue;
                    if (!merged.contains(p)) merged.add(p);
                }
            }
        }
        pinyinInsertPos = merged.size();   // v0.5.36 反馈⑨：拼音候选插到本地五笔候选之后
        candidates.addAll(merged);
        candPage = 0;
        if (candidates.isEmpty()) candidates.add(code);
        updateCandidateView();
        // ④ 云端热点词组：异步回填，插到 MRU 段之后、五笔之前
        if (GATEWAY_READY) {
            cloudInsertPos = merged.size();
            queryGatewayAsync(code);
        }
        // v0.5.35 反馈⑥：≤4 码也异步查拼音（如 nihao 前 4 码 niha 期间，候选追加"你好"类辅助）
        if (code.length() >= 2) queryPinyin(code, false);
    }

    /** v0.5.0 反馈⑤：英文/HTML 自动补全（输入 ht→https://、@→邮箱后缀、.→域名后缀） */
    private void checkEnglishCompletion(String code) {
        String lower = code.toLowerCase();
        if (lower.startsWith("ht") && lower.length() <= 5) {
            candidates.add("https://");
            candidates.add("http://");
        } else if (code.endsWith("@")) {
            candidates.add("@qq.com");
            candidates.add("@163.com");
            candidates.add("@gmail.com");
            candidates.add("@outlook.com");
            candidates.add("@foxmail.com");
        } else if (code.endsWith(".")) {
            candidates.add(".com");
            candidates.add(".cn");
            candidates.add(".net");
            candidates.add(".org");
            candidates.add(".io");
        } else if (code.endsWith("/")) {
            candidates.add("www.");
        } else if (code.length() >= 2 && code.length() <= 4) {
            String[] common = {"the", "and", "com", "org", "net", "http", "www", "www."};
            for (String w : common) {
                if (w.startsWith(lower) && !candidates.contains(w)) candidates.add(w);
            }
        }
    }

    /** 云端网关查询（异步：后台请求，主线程回填，不阻塞输入） */
    private void queryGatewayAsync(final String code) {
        final Handler handler = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            List<String> cloud = new ArrayList<>();
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                String body = "{\"code\":\"" + code + "\",\"phrase\":true}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        List<String> parsed = parseCandidates(sb.toString());
                        if (parsed != null) cloud.addAll(parsed);
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) { }
            final List<String> result = cloud;
            handler.post(() -> {
                // 仅当编码仍一致时回填，避免过期结果覆盖
                if (!code.equals(composingCode.toString())) return;
                // v0.5.0 反馈②：热点词组插到 MRU 段之后、传统五笔之前（排第二位的五笔不动）
                // v0.5.2 反馈⑥：云端回填同样过滤已上屏字/词
                // v0.5.6：整码(4码)且本地候选全是单字 → 云端词组置顶（四码词组优先全局生效，
                //        解决"常用词组打不出来"——云端 5.7 万词组全量兜底）
                int pos = Math.min(Math.max(cloudInsertPos, 0), candidates.size());
                if (code.length() == 4) {
                    boolean localHasPhrase = false;
                    for (String c : candidates) {
                        if (c.length() > 1) { localHasPhrase = true; break; }
                    }
                    if (!localHasPhrase) pos = 0;
                }
                int inserted = 0;
                for (String s : result) {
                    if (isJustCommitted(s)) continue;
                    // v0.5.6 fix：整码置顶时只置顶词组（云端单字本地已全，置顶会挤占词组位）
                    if (pos == 0 && s.length() < 2) continue;
                    if (!candidates.contains(s)) {
                        candidates.add(pos + inserted, s);
                        inserted++;
                        if (inserted >= 8) break;
                    }
                }
                if (inserted > 0) updateCandidateView();
            });
        });
        t.start();
    }

    /** v0.5.31 云端词库统计（词条数上报：点击"云五笔"弹窗显示分类词库规模） */
    private int cloudCatCount = 0;
    private int cloudCatWords = 0;

    private List<String> parseCandidates(String json) {
        List<String> list = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(json);
            // v0.5.16 反馈①：词组（phrases）优先于单字（candidates）——四码"dgqe"的感触/三角不被截断
            JSONArray phrases = obj.optJSONArray("phrases");
            if (phrases != null) {
                for (int i = 0; i < phrases.length(); i++) {
                    String p = phrases.getString(i);
                    if (p != null && p.length() >= 2) list.add(p);
                }
            }
            // v0.5.17 反馈②③（举一反三）：动态构词（gen）排真词组之后——ilif 未收录时"渐法/水国法"等
            //   无意义组合不挡道；补词后"没办法"（lexicon）在 phrases 里优先返回
            // v0.5.31 词条数上报：云端分类词库规模（弹窗显示）
            if (obj.has("cat_count")) {
                cloudCatCount = obj.optInt("cat_count", 0);
                cloudCatWords = obj.optInt("cat_words", 0);
            }
            JSONArray gen = obj.optJSONArray("gen");
            if (gen != null) {
                for (int i = 0; i < gen.length(); i++) {
                    String p = gen.getString(i);
                    if (p != null && p.length() >= 2) list.add(p);
                }
            }
            JSONArray cps = obj.optJSONArray("candidates");
            if (cps != null) {
                for (int i = 0; i < cps.length(); i++) {
                    int cp = cps.getInt(i);
                    if (cp > 0) list.add(new String(Character.toChars(cp)));
                }
            }
        } catch (Exception ignored) { }
        return list;
    }

    /** 候选条渲染：空闲态（云五笔 ▾ 剪贴板）/ 剪贴板历史 / 数字计算 / 候选列表 */
    private void updateCandidateView() {
        if (candidateView == null) return;
        // v0.5.13 反馈①：app 信息面板优先渲染
        if (infoPanelMode) {
            renderInfoPanel();
            return;
        }
        // v0.5.11 反馈⑥：剪贴板态优先渲染（英文输入态也可用剪贴板，原英文分支提前 return 导致不可用）
        if (clipMode) {
            renderClipboardList();
            return;
        }
        // v0.5.34 反馈①：数字面板实时计算——候选条显示"带式"和"仅结果"两个候选，点选上屏
        if (panelMode == 1 && !calcBuffer.isEmpty()) {
            renderCalcCandidates();
            return;
        }
        // v0.5.14 反馈⑤：恢复正常态固定单行高度（剪贴板态放宽后恢复，防画面抖动）
        candidateView.setMaxLines(1);
        candidateView.setMinHeight(candViewH);
        candidateView.setMaxHeight(candViewH);
        // v0.5.8 反馈⑨：英文模式——候选条渲染字母串 + 自动补全建议（原只显示 EN，用户看不到输入导致"打不上字"）
        if (!chineseMode) {
            String ec = composingCode.toString();
            if (statusInfo != null) statusInfo.setText(ec);
            if (!candidates.isEmpty() || !ec.isEmpty()) {
                int tColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
                int eColor = dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT;
                SpannableStringBuilder esb = new SpannableStringBuilder();
                int es0 = esb.length();
                esb.append(ec).append("  ");
                esb.setSpan(new ForegroundColorSpan(eColor), es0, es0 + ec.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                for (int i = 0; i < candidates.size(); i++) {
                    String c = candidates.get(i);
                    int s = esb.length();
                    esb.append(String.valueOf(i + 1)).append(".").append(c).append("  ");
                    int e = esb.length();
                    final int idx = i;
                    ClickableSpan cs = new ClickableSpan() {
                        @Override
                        public void onClick(View widget) {
                            selectCandidate(idx);
                        }
                        @Override
                        public void updateDrawState(android.text.TextPaint ds) {
                            ds.setColor(tColor);
                            ds.setUnderlineText(false);
                        }
                    };
                    esb.setSpan(cs, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                candidateView.setSingleLine(true);   // v0.5.11 反馈②：英文候选单行不换行
                candidateView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                candidateView.setText(esb);
            } else {
                setHintText("EN");
                if (statusInfo != null) statusInfo.setText("");
            }
            return;
        }
        String code = composingCode.toString();
        // v0.5.8 反馈①：编码实时回显到状态栏（备选栏只显示候选）
        if (statusInfo != null) {
            String info = code;
            if (!lastEnHint.isEmpty()) info += "  EN: " + lastEnHint;
            statusInfo.setText(info);
        }
        // v0.5.8 反馈①：数字面板计算式同步状态栏
        if (panelMode == 1 && statusInfo != null) {
            statusInfo.setText(calcBuffer.isEmpty() ? "123" : calcBuffer);
        }
        // v0.4.8 反馈③：非剪贴板状态恢复默认行距
        candidateView.setLineSpacing(0f, 1.0f);
        // v0.4.8 反馈⑧：数字面板计算状态实时显示（v0.5.1 候选条提供 带式/仅结果 两种上屏）
        if (panelMode == 1 && !calcBuffer.isEmpty()) {
            renderCalcState();
            return;
        }
        // v0.5.1：数字面板空缓冲提示
        if (panelMode == 1) {
            candidateView.setText("");   // v0.5.9 反馈④：去掉自以为是提示
            return;
        }
        if (clipMode) {
            renderClipboardList();
            return;
        }
        // v0.5.11 反馈②：候选/联想单行显示（不换行），英文翻译只显示在第一行状态栏
        if (code.isEmpty()) {
            // v0.5.8 反馈①：编码清空 → 状态栏同步（空闲时无编码，翻译如存在则上移状态栏）
            if (statusInfo != null) statusInfo.setText(lastEnHint.isEmpty() ? "" : "EN: " + lastEnHint);
            // v0.5.20：联想已去除——上屏后不显示"最近上屏 ▸ 联想词"（renderAssociateHint 不再调用）
            if (candidates.isEmpty()) {
                // v0.5.28 反馈⑥：空闲态也显示剪贴板项（复制后打开输入法立即可见，点选上屏）
                String clipTxt = getClipboardText();
                if (!clipTxt.isEmpty()) {
                    String disp = clipTxt.length() > 4 ? clipTxt.substring(0, 4) + "…" : clipTxt;
                    SpannableStringBuilder csb = new SpannableStringBuilder();
                    int cs0 = csb.length();
                    csb.append("📋").append(disp);
                    int cs1 = csb.length();
                    final String clipFinal = clipTxt;
                    ClickableSpan clipCs = new ClickableSpan() {
                        @Override
                        public void onClick(View widget) {
                            commitText(clipFinal);
                        }
                        @Override
                        public void updateDrawState(android.text.TextPaint ds) {
                            ds.setColor(dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT);
                            ds.setUnderlineText(false);
                        }
                    };
                    csb.setSpan(clipCs, cs0, cs1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    candidateView.setText(csb);
                } else {
                    candidateView.setText("");
                }
            }
            return;
        }
        renderCandidates();
    }

    /** v0.4.8 数字面板：实时显示 表达式=结果 */
    /** v0.5.1 反馈②：数字面板实时计算；v0.5.15 反馈③：去掉〔带式〕〔结果〕候选，上屏走 = 键（带式默认） */
    private void renderCalcState() {
        if (calcBuffer.isEmpty()) {
            candidateView.setText("");   // v0.5.9 反馈④：去掉自以为是提示
            return;
        }
        double v = calcEval(calcBuffer);
        String res = fmtResult(v);
        int textColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int s0 = sb.length();
        sb.append(calcBuffer);
        sb.setSpan(new ForegroundColorSpan(textColor), s0, s0 + calcBuffer.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(" = ").append(res);
        candidateView.setText(sb);
    }

    /** v0.4.8 反馈①⑦：上屏单字后的联想词组（MRU 置顶 + 本地锚字前缀词组 + 云端热点）
     *  v0.5.11 反馈②：英文翻译不在此显示（仅第一行状态栏），且单行不换行 */
    private void renderAssociateHint() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(lastCommittedText).append(" ▸ ");
        if (!candidates.isEmpty()) {
            int total = candidates.size();
            int pages = Math.max(1, (total + CAND_PAGE_SIZE - 1) / CAND_PAGE_SIZE);
            if (candPage >= pages) candPage = pages - 1;
            int from = candPage * CAND_PAGE_SIZE;
            int to = Math.min(total, from + CAND_PAGE_SIZE);
            for (int i = from; i < to; i++) {
                String c = candidates.get(i);
                int s = sb.length();
                sb.append(String.valueOf(i + 1)).append(".").append(c).append("  ");
                int e = sb.length();
                final int idx = i;
                ClickableSpan cs = new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        selectCandidate(idx);
                    }
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        ds.setColor(dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT);
                        ds.setUnderlineText(false);
                    }
                };
                sb.setSpan(cs, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            appendPager(sb, pages);
        } else {
            sb.append("（暂无联想，继续输入编码）");
        }
        candidateView.setText(sb);
    }

    /** v0.4.9 候选翻页指示 ◀ N/M ▶（点击翻页；v0.5.3 反馈⑦：颜色跟随系统） */
    private void appendPager(SpannableStringBuilder sb, int pages) {
        if (pages <= 1) return;
        int pagerColor = dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT;
        int pStart = sb.length();
        sb.append("  ◀ ").append(String.valueOf(candPage + 1)).append("/").append(String.valueOf(pages)).append(" ▶");
        int prevStart = pStart + 2;
        int prevEnd = prevStart + 1;
        int nextStart = sb.length() - 2;
        int nextEnd = nextStart + 1;
        ClickableSpan prevCs = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                if (candPage > 0) candPage--;
                updateCandidateView();
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(pagerColor);
                ds.setUnderlineText(false);
            }
        };
        ClickableSpan nextCs = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                candPage++;
                updateCandidateView();
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(pagerColor);
                ds.setUnderlineText(false);
            }
        };
        sb.setSpan(prevCs, prevStart, prevEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(nextCs, nextStart, nextEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(pagerColor), pStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private boolean dark() {
        return theme == THEME_DARK;
    }

    private void setHintText(String text) {
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new ForegroundColorSpan(dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        candidateView.setText(ss);
    }

    /** 剪贴板历史列表（仅复制文本，最新在前，最多 8 条显示；点击上屏）
     *  v0.5.3 反馈④：2 倍行距 + 灰色下横线分隔 + 跟随系统色
     *  v0.5.11 反馈②：剪贴板列表恢复多行（候选单行仅限候选/联想态） */
    private void renderClipboardList() {
        // v0.5.14 反馈⑤：剪贴板态放宽高度（多行列表），其他态固定单行
        int dp8 = Math.round(8 * getResources().getDisplayMetrics().density);
        candidateView.setMinHeight(dp8 * 10);
        candidateView.setMaxHeight(dp8 * 10);
        candidateView.setMaxLines(10);
        candidateView.setSingleLine(false);
        candidateView.setEllipsize(null);
        // v0.5.9 反馈⑨：适度行距（0,1.3f 非增大 extra）——条目间用浅色相间背景区分（非虚横线、非空行）
        candidateView.setLineSpacing(0f, 1.3f);
        int textColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        int altBg = dark() ? 0x2AFFFFFF : 0xFFF3F3F3;   // 相间浅色背景（跟随深浅色）
        StringBuilder sb = new StringBuilder();
        sb.append("◀ 返回   ");
        if (clipHistory.isEmpty()) {
            sb.append("（剪贴板暂无历史，复制的文本将自动记录）");
        } else {
            sb.append("剪贴板（").append(clipHistory.size()).append("）\n");
            int shown = Math.min(8, clipHistory.size());
            for (int i = 0; i < shown; i++) {
                String item = clipHistory.get(i);
                String line = item.replace('\n', ' ');
                if (line.length() > 18) line = line.substring(0, 18) + "…";
                sb.append("  ").append(i + 1).append("·").append(line).append("\n");
            }
        }
        SpannableStringBuilder css = new SpannableStringBuilder(sb.toString());
        ClickableSpan backCs = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                clipMode = false;
                updateCandidateView();
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(textColor);
                ds.setUnderlineText(false);
            }
        };
        css.setSpan(backCs, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int shown = Math.min(8, clipHistory.size());
        for (int i = 0; i < shown; i++) {
            final String item = clipHistory.get(i);
            String marker = "  " + (i + 1) + "·";
            int idx = sb.indexOf(marker, 8);
            if (idx < 0) continue;
            int start = idx + marker.length();
            int end = sb.indexOf("\n", start);
            if (end < 0) end = sb.length();
            ClickableSpan cs = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    commitText(item);   // v0.4.9 自动记录 undo
                    clipMode = false;   // v0.5.36 反馈②：点选剪贴板项后自动关闭（不再常驻）
                    updateCandidateView();
                    clipMode = false;
                    updateCandidateView();
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(textColor);
                    ds.setUnderlineText(false);
                }
            };
            css.setSpan(cs, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // v0.5.34 反馈⑧：每项末尾加"✕"删除链接（点击删除该项）
            int xs = end;
            css.append(" ✕");
            final int fi = i;
            css.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    removeClipItem(fi);
                    updateCandidateView();
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(0xFFDC2626);
                    ds.setUnderlineText(false);
                }
            }, xs, end + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // v0.5.34 反馈⑧：ClipTagSpan 标记整项（长按定位删除）
            css.setSpan(new ClipTagSpan(fi), idx, end + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // v0.5.9 反馈⑨：浅色相间背景（隔行着色）替代虚横线，视觉区分且不增加行高
            if (i % 2 == 1) {
                css.setSpan(new android.text.style.BackgroundColorSpan(altBg), idx, end + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        candidateView.setText(css);
    }

    /** 候选列表：v0.5.3 反馈③⑦——纯候选（去杂项）；v0.5.4 反馈④：编码前缀实时显示（含删除时同步）
     *  v0.5.11 反馈②：单行不换行 */
    /** v0.5.35 反馈③：候选左右滑动翻页 */
    private void nextCandidatePage() {
        int pages = Math.max(1, (candidates.size() + CAND_PAGE_SIZE - 1) / CAND_PAGE_SIZE);
        if (candPage < pages - 1) { candPage++; updateCandidateView(); }
    }
    private void prevCandidatePage() {
        if (candPage > 0) { candPage--; updateCandidateView(); }
    }

    /** v0.5.35 反馈①：表达式是否已含运算符（决定数字是否进缓冲） */
    private boolean hasCalcOp(String expr) {
        return expr.indexOf('+') >= 0 || expr.indexOf('-') >= 0 || expr.indexOf('*') >= 0
                || expr.indexOf('/') >= 0 || expr.indexOf('×') >= 0 || expr.indexOf('÷') >= 0;
    }

    /** v0.5.34 反馈①：数字面板实时计算候选（带式 / 仅结果，点选上屏，上屏后可续算）
     *  v0.5.35 反馈①：表达式不含运算符（纯数字直接上屏）时清空候选，不弹"1.5=5 2.5" */
    private void renderCalcCandidates() {
        if (calcBuffer.isEmpty() || !hasCalcOp(calcBuffer)) {
            candidateView.setText("");
            return;
        }
        double v = calcEval(calcBuffer);
        if (Double.isNaN(v)) {
            candidateView.setSingleLine(true);
            candidateView.setText(calcBuffer);
            return;
        }
        final String res = fmtResult(v);
        final String full = calcBuffer + "=" + res;
        int textColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        SpannableStringBuilder csb = new SpannableStringBuilder();
        int s0 = csb.length();
        csb.append("1.").append(full).append("   ");
        int e0 = csb.length();
        csb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                commitCalcAs(true);
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(textColor);
                ds.setUnderlineText(false);
            }
        }, s0, e0, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int s1 = csb.length();
        csb.append("2.").append(res).append("   ");
        int e1 = csb.length();
        csb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                commitCalcAs(false);
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(textColor);
                ds.setUnderlineText(false);
            }
        }, s1, e1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        candidateView.setSingleLine(true);
        candidateView.setText(csb);
    }

    /** v0.5.34 反馈①：计算候选上屏（withFormula=带式/仅结果），结果保留供运算符续算 */
    private void commitCalcAs(boolean withFormula) {
        if (calcBuffer.isEmpty()) return;
        double v = calcEval(calcBuffer);
        if (Double.isNaN(v)) {
            commitText(calcBuffer);
            lastCalcResult = "";
        } else {
            String res = fmtResult(v);
            lastCalcResult = res;
            if (withFormula) {
                commitText(calcBuffer + "=" + res);
            } else {
                commitText(res);
            }
        }
        calcBuffer = "";
        calcAuto = false;
        if (!lastCalcResult.isEmpty()) lastCalcInput = lastCalcResult;
        updateCandidateView();
    }

    private void renderCandidates() {
        candidateView.setSingleLine(true);
        candidateView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String code = composingCode.toString();
        int total = candidates.size();
        int pages = Math.max(1, (total + CAND_PAGE_SIZE - 1) / CAND_PAGE_SIZE);
        if (candPage >= pages) candPage = pages - 1;
        int from = candPage * CAND_PAGE_SIZE;
        int to = Math.min(total, from + CAND_PAGE_SIZE);
        int textColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        // v0.5.8 反馈①：编码实时回显已移至状态栏（statusInfo），备选栏仅显示候选
        for (int i = from; i < to; i++) {
            String c = candidates.get(i);
            int s = sb.length();
            sb.append(String.valueOf(i + 1)).append(".").append(c).append("  ");
            int e = sb.length();
            final int idx = i;
            ClickableSpan cs = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    selectCandidate(idx);
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(textColor);
                    ds.setUnderlineText(false);
                }
            };
            sb.setSpan(cs, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // v0.4.9 翻页指示（跟随系统色）
        appendPager(sb, pages);
        candidateView.setText(sb);
    }

    /** v0.4.8 反馈④：回车——无候选时上屏换行；v0.5.3 反馈⑥：单行文本框回车无反应（不换行不空格）
     *  v0.5.11 反馈④：按回车 → 上屏当前编码的小写英文（qq → qq），不再误选中文候选（多） */
    private void commitFirstCandidate() {
        // v0.5.35 反馈⑥：超 4 码（拼音全拼模式）回车上屏首选候选（nihao → 你好）
        if (composingCode.length() > 4 && !candidates.isEmpty()) {
            selectCandidate(0);
            return;
        }
        if (composingCode.length() > 0) {
            String raw = composingCode.toString();
            composingCode.setLength(0);
            candidates.clear();
            candPage = 0;
            commitText(raw);
            updateCandidateView();
        } else if (isMultiline()) {
            commitText("\n");
        }
    }

    /** v0.5.3 反馈⑥：当前输入框是否多行（多行才允许回车换行） */
    private boolean isMultiline() {
        try {
            EditorInfo ei = getCurrentInputEditorInfo();
            return ei != null && (ei.inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** v0.5.11 反馈④：空格——备选栏第一个是中文候选则上中文；候选仅有编码本身（无中文命中）则输出空格 */
    private void commitSpaceOrFirst() {
        if (composingCode.length() > 0 && !candidates.isEmpty()
                && !candidates.get(0).equals(composingCode.toString())) {
            selectCandidate(0);
        } else {
            commitText(" ");
        }
    }

    /** v0.4.8 数字面板：= 或退出时上屏。带公式（默认）上屏 "1+2=3"；纯数字直接上屏 */
    /** v0.5.3 反馈②：数字面板运算符——上次结果上屏后接运算符自动续算（8 → +2 → 8+2=10） */
    private void calcAppendOp(String op) {
        // v0.5.35 反馈①：calcBuffer 空时——先续接"上次计算结果"，再回收"刚直接上屏的数字"（5 → + → "5+"）
        if (calcBuffer.isEmpty() && !lastCalcResult.isEmpty()) {
            calcBuffer = lastCalcResult + op;
            calcAuto = true;    // v0.5.8 反馈⑥：运算符自动续接上次结果 → 去重合法（8 → +2 → 上屏"+2=10"）
        } else if (calcBuffer.isEmpty() && !lastCalcInput.isEmpty()) {
            calcBuffer = lastCalcInput + op;
            lastCalcInput = "";
            calcAuto = true;
        } else {
            calcBuffer += op;
            calcAuto = false;   // 手动完整输入 → 不去重（3*2 不以结果 3 开头省略）
        }
        updateCandidateView();
    }

    /** v0.5.27 反馈②：读取系统剪贴板文本（复制的文本→备选栏点选上屏） */
    private String getClipboardText() {
        if (clipManager == null || !clipManager.hasPrimaryClip()) return "";
        try {
            android.content.ClipData cd = clipManager.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return "";
            CharSequence t = cd.getItemAt(0).coerceToText(this);
            if (t == null) return "";
            String s = t.toString().trim();
            return s.isEmpty() ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private void commitCalc(boolean fromEq) {
        if (calcBuffer.isEmpty()) return;
        double v = calcEval(calcBuffer);
        if (Double.isNaN(v)) {
            commitText(calcBuffer);
            lastCalcResult = "";
        } else {
            String res = fmtResult(v);
            // v0.5.11 反馈③：★先保存上一次结果作为去重基准，再更新 lastCalcResult
            // （原代码先覆盖 lastCalcResult=新结果，导致 expr.startsWith(新结果) 恒 false → 不去重 → 1+2=33*4=12）
            String oldResult = lastCalcResult;
            lastCalcResult = res;
            if (calcBuffer.matches("^[0-9.]+$")) {
                commitText(calcBuffer);
            } else if (calcFormula) {
                // v0.5.32 计算器去重科学化（根治"1+2=33*4=12"反复复发）：
                //   不再依赖 calcAuto 标志（易被面板切换等路径破坏）——直接读文本框末尾验证
                //   "上次结果是否已在屏"，在屏则省略重复（1+2=3 上屏后 *4 → 上屏"*4=12"，
                //   文本框拼接 = "1+2=3*4=12"）；不在屏（手动完整输入 3*2）则完整上屏"3*2=6"
                String expr = calcBuffer;
                boolean hasResult = false;
                if (!oldResult.isEmpty()) {
                    InputConnection cic = getCurrentInputConnection();
                    try {
                        CharSequence tb = cic == null ? null : cic.getTextBeforeCursor(oldResult.length(), 0);
                        if (tb != null && tb.toString().equals(oldResult)) hasResult = true;
                    } catch (Exception ignored) { }
                }
                if (hasResult && expr.startsWith(oldResult)) {
                    expr = expr.substring(oldResult.length());
                    if (expr.isEmpty()) expr = res;
                }
                commitText(expr + "=" + res);
            } else {
                commitText(res);
            }
        }
        calcBuffer = "";
        calcAuto = false;
        if (!lastCalcResult.isEmpty()) lastCalcInput = lastCalcResult;   // v0.5.35 续算起点
        // v0.5.4 反馈⑥：结果上屏后保留数字面板——继续按运算符接续计算（2+3=5 → + → 5+）
        panelMode = 1;
        keyboardView.setKeyboard(keyboardNum);
        updateCandidateView();
    }

    private boolean associateActive = false;   // v0.4.9 联想态标记（用于连续联想链）
    private int cloudInsertPos = 0;            // v0.5.0 云端热点插入位（MRU 段后）
    /** v0.5.3 反馈①：最近一次上屏的字/词（过滤键，仅滤"紧接着重复出现"，不累积，避免高频字消失） */
    private String committedLast = "";

    /** v0.5.3 反馈①：候选是否为"刚上屏过"的同一内容（只滤最近一次，MRU/高频字不受影响） */
    private boolean isJustCommitted(String c) {
        return !committedLast.isEmpty() && c != null && c.equals(committedLast);
    }

    private void selectCandidate(int idx) {
        if (idx < 0 || idx >= candidates.size()) return;
        String text = candidates.get(idx);
        // v0.5.12 根治（用户已 5-6 次反馈）：前缀去重——候选以"刚上屏内容/上次结果"开头时，
        //   先删除光标前的旧内容再上屏（所见即所得）：
        //   · 上屏"宇"后点联想"宇宙" → 得"宇宙"（非"宇宇宙"）
        //   · 上屏"陈"后点"陈胜" → 得"陈胜"；再点"陈胜吴广" → 得"陈胜吴广"（联想链正常）
        //   · 计算 8*4=32 后点带式"32/16=2"（若走此路径）→ 得"/16=2"（非"32/16=2"重复）
        if (chineseMode) {
            String base = !committedLast.isEmpty() ? committedLast : lastCalcResult;
            if (!base.isEmpty() && base.length() < text.length() && text.startsWith(base)) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    try {
                        CharSequence before = ic.getTextBeforeCursor(base.length(), 0);
                        if (before != null && before.toString().equals(base)) {
                            pushUndo();
                            ic.deleteSurroundingText(base.length(), 0);
                        }
                    } catch (Exception ignored) { }
                }
            }
        }
        // v0.5.0 反馈⑤：英文补全——直接上屏补全串，不进五笔联想链
        if (!chineseMode) {
            commitText(text);
            composingCode.setLength(0);
            candPage = 0;
            updateCandidateView();
            return;
        }
        commitText(text);   // v0.4.9 自动记录 undo 快照
        lastSelected = text;   // MRU 置顶
        prefs.edit().putString(PREFS_LAST_SEL, text).apply();   // v0.5.9 反馈⑧：持久化字频调整
        bumpFreq(text);   // v0.5.14 反馈②：端侧词频累计（最近 3 个月输入记录）
        // v0.5.3 反馈①：记录最近一次上屏的字/词（仅滤紧接着的重复出现）
        committedLast = text;
        if (GATEWAY_READY) reportSelection(text);
        composingCode.setLength(0);
        candidates.clear();
        // v0.5.20（用户指令）：联想功能暂时全部去除——不拼接联想链、不触发字后/连续/云端联想
        lastCommittedText = text;
        if (text.length() >= 2) rememberPhrase(text);   // MRU（最近 3 词组）保留（记忆，非联想）
        lastEnHint = "";
        // v0.5.5 反馈①：密码框禁翻译（隐私）
        if (isPassword) {
            updateCandidateView();
            return;
        }
        String lastChar = lastCommittedText.isEmpty() ? "" : lastCommittedText.substring(lastCommittedText.length() - 1);
        if (!lastChar.isEmpty()) {
            queryTranslation(lastChar);   // 仅保留状态栏英文翻译（v0.5.13 要求，非联想）
        }
        updateCandidateView();
    }

    /** v0.4.9 反馈① + v0.5.1 反馈⑤：联想基准=整个上屏词组（如"前进"→"前进浪潮/前进号角"），
     *  不再按单字含字联想（避免"驶进/共进"式不合理联想）；MRU 置顶 + 整词前缀 + 锚字前缀 + 云端 */
    private void triggerAssociate() {
        // v0.5.0 反馈③：联想锚字取光标前一字（删除光标前字、移动光标后自动重新联想）
        String anchor = getCursorPrevChar();
        if (anchor.isEmpty()) {
            if (lastCommittedText.isEmpty()) return;
            anchor = lastCommittedText.substring(lastCommittedText.length() - 1);
        }
        String chain = lastCommittedText;   // v0.5.1：联想基准=整个已上屏词组（整词，非单字）
        List<String> merged = new ArrayList<>();
        // ① MRU：最近选中的词组（以整词开头 或 含整词）置顶
        for (String p : recentPhrases) {
            if (p.startsWith(chain) && !merged.contains(p)) merged.add(p);
        }
        // ② 整词前缀联想：本地词库以整词开头（前进→前进浪潮/前进号角）
        if (!chain.isEmpty()) {
            List<String> prefix = WubiDb.queryByPrefix(chain);
            if (prefix != null) {
                for (String p : prefix) if (!merged.contains(p)) merged.add(p);
            }
        }
        // ②b v0.5.11 反馈⑤：锚字前缀联想（进→进一步/进行/进入/进攻…，光标前一字开头的常用搭配）
        //   （原 queryByChar 含字联想会返回"共进/驶进"等 X进 结尾词——方向错误，已废弃）
        if (merged.size() < 12 && !anchor.isEmpty()) {
            List<String> byPrefix = WubiDb.queryByPrefix(anchor);
            if (byPrefix != null) {
                for (String p : byPrefix) {
                    if (!merged.contains(p)) merged.add(p);
                    if (merged.size() >= 12) break;
                }
            }
        }
        // ②c v0.5.9 反馈②：积极成语联想层（含锚字成语——阳光向上、有启发有感悟、眼前一亮）
        if (merged.size() < 12 && !anchor.isEmpty()) {
            List<String> idms = WubiDb.queryIdioms(anchor);
            for (String p : idms) {
                if (!merged.contains(p)) merged.add(p);
                if (merged.size() >= 12) break;
            }
        }
        // v0.5.14 反馈②：端侧词频排序（结合最近 3 个月输入记录——MRU 置顶保持，其余按上屏频次降序）
        List<String> ordered = new ArrayList<>();
        for (String p : recentPhrases) {
            if (merged.contains(p) && !ordered.contains(p)) ordered.add(p);
        }
        List<String> rest = new ArrayList<>();
        for (String p : merged) {
            if (!ordered.contains(p)) rest.add(p);
        }
        // 稳定降序：频次高者前（同频保持原顺序——本地词库序/成语序不被打乱）
        for (int i = 1; i < rest.size(); i++) {
            String k = rest.get(i);
            int j = i - 1;
            while (j >= 0 && freqOf(rest.get(j)) < freqOf(k)) {
                rest.set(j + 1, rest.get(j));
                j--;
            }
            rest.set(j + 1, k);
        }
        ordered.addAll(rest);
        candidates.clear();
        candidates.addAll(ordered);
        candPage = 0;
        updateCandidateView();
        // ③ 云端热点：上下文通道（整句上文→语境连续联想）+ 锚字前缀兜底
        if (GATEWAY_READY) queryAssociateAsync(chain, anchor);
    }

    /** v0.5.0 反馈③：读取光标前一个字（联想锚字来源） */
    private String getCursorPrevChar() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        try {
            CharSequence cb = ic.getTextBeforeCursor(1, 0);
            if (cb != null && cb.length() > 0) return cb.toString();
        } catch (Exception ignored) { }
        return "";
    }

    /** v0.5.0 反馈③：光标移动/编辑后刷新联想（空闲态且中文模式） */
    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        if (!chineseMode || composingCode.length() > 0 || !associateActive) return;
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            String prev = getCursorPrevChar();
            if (!prev.isEmpty()) {
                String lastCharOfChain = lastCommittedText.isEmpty()
                        ? "" : lastCommittedText.substring(lastCommittedText.length() - 1);
                if (!prev.equals(lastCharOfChain)) {
                    lastCommittedText = prev;   // 光标移到新字前 → 联想链重置为新锚字
                    lastEnHint = "";
                }
            }
            triggerAssociate();   // 以新光标前字重新联想
        }
    }

    /** v0.6 反馈①②：云端上下文连续联想（{"context":"光标前8字上文"} → 语境连续联想）
     *  革命性：不再单字联想，结合输入框上下文/前后文，参考主流输入法联想原理的云端实现 */
    private void queryAssociateAsync(final String chain, final String lastChar) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final String context = getContextBefore(8);   // 收集光标前 8 字整句上文
        Thread t = new Thread(() -> {
            List<String> cloud = new ArrayList<>();
            try {
                // ① 上下文通道（主）：整句上文 → 成语启发 + 热词趋势 + bigram 顺承 + 学习词
                if (context != null && !context.isEmpty()) {
                    List<String> p1 = postGateway("{\"context\":\"" + jsonEscape(context) + "\"}");
                    if (p1 != null) cloud.addAll(p1);
                }
                // ② 锚字前缀通道（兜底）：光标前 1 字前缀联想
                if (cloud.size() < 12) {
                    String query = lastChar == null || lastChar.isEmpty() ? chain : lastChar;
                    List<String> p2 = postGateway("{\"prefix\":\"" + jsonEscape(query) + "\"}");
                    if (p2 != null) {
                        for (String s : p2) {
                            if (!cloud.contains(s)) cloud.add(s);
                        }
                    }
                }
            } catch (Exception ignored) { }
            final List<String> result = cloud;
            handler.post(() -> {
                // v0.5.5 反馈⑦：联想基准=上屏链 或 进入输入状态的光标前字，两者一致才回填
                if (!chain.equals(lastCommittedText) && !chain.equals(enterAssociateChar)) return;
                boolean changed = false;
                for (String s : result) {
                    if (!candidates.contains(s)) { candidates.add(s); changed = true; }
                }
                if (changed) updateCandidateView();
            });
        });
        t.start();
    }

    /** v0.6 反馈①：读取光标前 n 字（上下文连续联想输入） */
    private String getContextBefore(int n) {
        try {
            InputConnection ic = getCurrentInputConnection();
            CharSequence cb = ic == null ? null : ic.getTextBeforeCursor(n, 0);
            return cb == null ? "" : cb.toString();
        } catch (Exception e) { return ""; }
    }

    /** v0.6：JSON 字符串转义（上文可能含引号/反斜杠） */
    private String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 云端 POST 请求（返回 phrases 数组，失败返回 null） */
    private List<String> postGateway(String body) {
        List<String> cloud = new ArrayList<>();
        try {
            URL url = new URL(GATEWAY_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    JSONObject obj = new JSONObject(sb.toString());
                    JSONArray ps = obj.optJSONArray("phrases");
                    if (ps != null) {
                        for (int i = 0; i < ps.length(); i++) {
                            String p = ps.getString(i);
                            if (p != null && p.length() >= 2) cloud.add(p);
                        }
                    }
                }
            }
            conn.disconnect();
        } catch (Exception ignored) { }
        return cloud;
    }

    /** v0.4.8 反馈⑦ + v0.4.9：选字后云端英文翻译（{"word":"钟","en":true} → "bell; clock"） */
    /** v0.5.35 反馈⑥：云端拼音/简拼查询（异步；isLong>4 码时拼音候选插最前——回车即上屏拼音首选） */
    private void queryPinyin(final String code, final boolean isLong) {
        if (!GATEWAY_READY || code.length() < 2) return;
        final int codeAtStart = composingCode.length();
        Thread t = new Thread(() -> {
            final java.util.List<String> cloud = new java.util.ArrayList<>();
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                String body = "{\"py\":\"" + code + "\"}";
                try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        JSONArray ps = new JSONObject(sb.toString()).optJSONArray("phrases");
                        if (ps != null) {
                            for (int i = 0; i < ps.length(); i++) {
                                String p = ps.getString(i);
                                if (p != null && p.length() >= 2) cloud.add(p);
                            }
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) { }
            final boolean insertFront = isLong;
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> {
                if (composingCode.length() != codeAtStart) return;   // 输入已变化，丢弃过期结果
                if (cloud.isEmpty()) return;
                boolean changed = false;
                for (int i = cloud.size() - 1; i >= 0; i--) {
                    String p = cloud.get(i);
                    if (candidates.contains(p)) continue;
                    // v0.5.37 反馈①：拼音候选恢复尾部（不抢位置）；仅 >4 码拼音模式插最前
                    if (insertFront) candidates.add(0, p); else candidates.add(p);
                    changed = true;
                }
                if (changed) updateCandidateView();
            });
        });
        t.start();
    }

    private void queryTranslation(final String word) {
        final Handler handler = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            final String chainAtStart = lastCommittedText;
            String en2 = "";
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                String body = "{\"word\":\"" + word + "\",\"en\":true}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        en2 = new JSONObject(sb.toString()).optString("en", "");
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) { }
            final String hint = en2;
            handler.post(() -> {
                if (!chainAtStart.equals(lastCommittedText)) return;
                lastEnHint = hint;
                updateCandidateView();
            });
        });
        t.start();
    }

    /** 上报选词（云端 MRU 学习，尽力而为） */
    private void reportSelection(final String phrase) {
        if (!GATEWAY_READY) return;
        Thread t = new Thread(() -> {
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                String body = "{\"learn\":\"" + phrase + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) { }
        });
        t.start();
    }
}
