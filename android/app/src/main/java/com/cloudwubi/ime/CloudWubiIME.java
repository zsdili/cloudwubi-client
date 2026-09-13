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
import java.util.HashSet;
import java.util.List;
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
    private static final int KEY_SYM_PREV = -112;  // v0.5.4 反馈⑤：符号面板"上一页"
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

    // ===== v0.4.9 取消↺ / 重做↻（编辑快照栈） =====
    private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> redoStack = new java.util.ArrayDeque<>();
    private static final int UNDO_MAX = 30;

    // ===== 剪贴板历史（仅复制文本，v0.4.5） =====
    private static final int CLIP_MAX = 20;
    private static final String PREFS_NAME = "cloudwubi";
    private static final String PREFS_CLIP = "clip_history";
    private static final String PREFS_PHRASES = "recent_phrases";  // v0.4.8 MRU 词组

    private final StringBuilder composingCode = new StringBuilder();
    private List<String> candidates = new ArrayList<>();
    private TextView candidateView;
    private CloudKeyboardView keyboardView;
    private LinearLayout rootView;
    private LinearLayout toolRow;   // v0.5.0 反馈①：工具行（全选/取消↺/重做↻）
    private android.widget.TextView hideBtn;   // v0.5.4 反馈⑨：闲置 2 秒后出现的收起键盘按钮
    /** v0.5.5：闲置定时器——1 秒清空备选栏（反馈⑧）+ 2 秒出现收起按钮（反馈④，INVISIBLE 占位不跳动） */
    private final android.os.Handler idleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idleClearRunnable = new Runnable() {
        @Override
        public void run() {
            // v0.5.5 反馈⑧：1 秒无输入 → 清空备选栏（正在输入的编码保留，仅清候选）
            candidates.clear();
            updateCandidateView();
        }
    };
    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            if (hideBtn != null && hideBtn.getVisibility() != android.view.View.VISIBLE) {
                hideBtn.setVisibility(android.view.View.VISIBLE);
            }
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
    private Keyboard keyboardSymbols;
    private Keyboard keyboardSymbols2;   // v0.5.3 反馈⑩：更多符号面板1
    private Keyboard keyboardSymbols3;   // v0.5.3 反馈⑩：更多符号面板2（末页）
    private boolean chineseMode = true;
    private int panelMode = 0;              // 0=主键盘 1=数字 2=符号
    private boolean clipMode = false;       // 候选条是否显示剪贴板历史
    private String lastSelected = "";       // MRU 置顶
    private String lastCommittedChar = "";  // v0.4.8 最近上屏单字（触发联想）
    private String lastEnHint = "";         // v0.4.8 最近选字英文翻译提示
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

    @Override
    public void onCreate() {
        super.onCreate();
        WubiDb.init(this);   // 加载离线词库（res/raw）
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadClipHistory();
        loadRecentPhrases();
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
        candidateView.setTextSize(15);
        candidateView.setPadding(14, 12, 14, 12);
        candidateView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        candidateView.setHighlightColor(0x00000000);

        keyboardMain = new Keyboard(this, R.xml.keyboard_qwerty);
        keyboardNum = new Keyboard(this, R.xml.keyboard_num);
        keyboardSymbols = new Keyboard(this, R.xml.keyboard_sym);
        keyboardSymbols2 = new Keyboard(this, R.xml.keyboard_sym2);
        keyboardSymbols3 = new Keyboard(this, R.xml.keyboard_sym3);
        keyboardView = new CloudKeyboardView(this, null);
        keyboardView.setKeyboard(keyboardMain);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);
        keyboardView.setHapticFeedbackEnabled(true);
        // 反馈④：键盘左右留边（截图约 4.5% 屏宽）
        int dp12 = Math.round(12 * getResources().getDisplayMetrics().density);
        int dp6 = Math.round(6 * getResources().getDisplayMetrics().density);
        keyboardView.setPadding(dp12, dp6, dp12, dp6);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(candidateView);
        // v0.5.0 反馈①：输入状态条工具行（全选 / 取消↺ / 重做↻）+ v0.5.1 反馈⑦：亖剪贴板置前
        toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setGravity(android.view.Gravity.CENTER);
        toolRow.setPadding(0, 3, 0, 3);
        toolRow.addView(makeToolButton("亖", v -> {
            // v0.5.5 反馈①：密码框禁用剪贴板（隐私）
            if (isPassword) return;
            clipMode = true;
            updateCandidateView();
        }));
        toolRow.addView(makeToolButton("全选", v -> selectAll()));
        toolRow.addView(makeToolButton("取消↺", v -> doUndo()));
        toolRow.addView(makeToolButton("重做↻", v -> doRedo()));
        // v0.5.4 反馈⑨ + v0.5.5 反馈④：闲置 2 秒未输入 → 工具行出现收起按钮（INVISIBLE 占位，出现时不跳动）
        hideBtn = makeToolButton("⌄", v -> {
            hideBtn.setVisibility(android.view.View.INVISIBLE);
            try { requestHideSelf(0); } catch (Exception ignored) { }
        });
        hideBtn.setVisibility(android.view.View.INVISIBLE);
        toolRow.addView(hideBtn);
        root.addView(toolRow);
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
    private TextView makeToolButton(String text, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setPadding(22, 6, 22, 6);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setOnClickListener(listener);
        return tv;
    }

    /** v0.5.0 反馈①：全选当前文本框内容 */
    private void selectAll() {
        InputConnection ic = getCurrentInputConnection();
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
        super.onFinishInput();
    }

    @Override
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
        enterAssociateChar = "";
        if (chineseMode && !isPassword) {
            String prev = getCursorPrevChar();
            if (!prev.isEmpty()) {
                enterAssociateChar = prev;
                showAssociateForChar(prev);
            }
        }
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
                }
            }
        } catch (Exception ignored) { }
    };

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
                k.label = chineseMode ? "中" : "EN";
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
        long now = System.currentTimeMillis();
        if (now - lastShiftTap < 350) {
            shiftState = 2;                    // 双击：锁定大写
        } else {
            shiftState = (shiftState == 0) ? 1 : 0;
        }
        lastShiftTap = now;
        applyLetterCase();
    }

    // ===== KeyboardView.OnKeyboardActionListener =====

    /** v0.5.4 反馈⑨ + v0.5.5 反馈④⑧：重置双闲置计时器（每次按键触发；1 秒清空备选栏、2 秒显示收起按钮） */
    private void resetIdleTimers() {
        idleHandler.removeCallbacks(idleClearRunnable);
        idleHandler.removeCallbacks(idleRunnable);
        if (hideBtn != null) hideBtn.setVisibility(android.view.View.INVISIBLE);
        idleHandler.postDelayed(idleClearRunnable, 1000);
        idleHandler.postDelayed(idleRunnable, 2000);
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        resetIdleTimers();   // v0.5.4 反馈⑨ + v0.5.5 反馈⑧：任何按键重置双闲置计时
        // 字母键
        if (primaryCode >= 'a' && primaryCode <= 'z') {
            if (chineseMode) {
                appendCode((char) primaryCode);
            } else {
                // v0.5.0 反馈⑤：英文输入进编码 → 自动补全候选（@邮箱、ht→https:// 等）
                appendEnglishCode((char) primaryCode);
                if (shiftState == 1) {          // 单次大写后复位
                    shiftState = 0;
                    applyLetterCase();
                }
            }
            return;
        }
        // 数字（v0.5.1：数字面板恢复实时计算——数字进表达式缓冲；其余直接上屏）
        if (primaryCode >= '0' && primaryCode <= '9') {
            if (panelMode == 1) {
                calcBuffer += (char) primaryCode;
                updateCandidateView();
            } else {
                commitText(String.valueOf((char) primaryCode));
            }
            return;
        }
        switch (primaryCode) {
            case KEY_123:
                panelMode = 1;
                calcBuffer = "";
                keyboardView.setKeyboard(keyboardNum);
                updateCandidateView();
                return;
            case KEY_SYM_IN:
                // v0.5.0：数字面板"符号"→进符号面板；v0.5.3 反馈⑩ + v0.5.4 反馈⑤：符号面板"下一页 ›"（sym1→sym2→sym3→sym1 循环）
                // v0.5.5 反馈⑤：从主键盘/数字面板进入符号面板时记录来源（prevPanel），供"返回"逐层回退
                if (panelMode == 2) {
                    panelMode = 3;
                    keyboardView.setKeyboard(keyboardSymbols2);
                } else if (panelMode == 3) {
                    panelMode = 4;
                    keyboardView.setKeyboard(keyboardSymbols3);
                } else if (panelMode == 4) {
                    panelMode = 2;
                    keyboardView.setKeyboard(keyboardSymbols);
                } else {
                    prevPanel = panelMode;
                    panelMode = 2;
                    keyboardView.setKeyboard(keyboardSymbols);
                }
                return;
            case KEY_SYM_PREV:   // v0.5.4 反馈⑤：符号面板"上一页 ‹"（sym1←sym2←sym3 循环）
                if (panelMode == 3) {
                    panelMode = 2;
                    keyboardView.setKeyboard(keyboardSymbols);
                } else if (panelMode == 4) {
                    panelMode = 3;
                    keyboardView.setKeyboard(keyboardSymbols2);
                } else {
                    panelMode = 4;
                    keyboardView.setKeyboard(keyboardSymbols3);
                }
                return;
            case KEY_SYMBOL:   // v0.5.5 反馈⑤：返回键逐层回退（sym3→sym2→sym1→来源面板→主键盘），数字面板有表达式先带式上屏
                if (panelMode == 4) {
                    panelMode = 3;
                    keyboardView.setKeyboard(keyboardSymbols2);
                    return;
                }
                if (panelMode == 3) {
                    panelMode = 2;
                    keyboardView.setKeyboard(keyboardSymbols);
                    return;
                }
                if (panelMode == 2) {
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
            default:
                String s = symbolToText(primaryCode);
                if (s != null) commitText(s);
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
        if (composingCode.length() >= 4) return;
        associateActive = false;   // v0.4.9 开始新编码 → 联想链断开
        candPage = 0;
        composingCode.append(c);
        queryCandidates();
    }

    /** v0.5.0 反馈⑤：英文输入进编码（放宽到 24 字符，触发补全候选） */
    private void appendEnglishCode(char c) {
        if (composingCode.length() >= 24) return;
        candPage = 0;
        composingCode.append(Character.toLowerCase(c));
        queryCandidates();
    }

    private void handleBackspace() {
        // v0.4.8：数字面板优先删计算表达式
        if (panelMode == 1 && !calcBuffer.isEmpty()) {
            calcBuffer = calcBuffer.substring(0, calcBuffer.length() - 1);
            updateCandidateView();
            return;
        }
        if (composingCode.length() > 0) {
            composingCode.deleteCharAt(composingCode.length() - 1);
            queryCandidates();
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            // v0.4.8 反馈②：全选/部分选中时删除整个选区（此前全选无反应、部分选中只删末字）
            CharSequence sel = null;
            try { sel = ic.getSelectedText(0); } catch (Exception ignored) { }
            // v0.5.3 反馈①：删除刚上屏的字后允许重新输入（清空过滤键）
            String prev = getCursorPrevChar();
            if (!prev.isEmpty() && prev.equals(committedLast)) committedLast = "";
            pushUndo();   // v0.4.9 取消↺ 可恢复删除
            if (sel != null && sel.length() > 0) {
                ic.commitText("", 0);
            } else {
                ic.deleteSurroundingText(1, 0);
            }
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
                if (code >= 33 && code <= 0xFFFF) {
                    return String.valueOf(Character.toChars(code));
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
        List<String> merged = new ArrayList<>();
        // v0.5.3 反馈①：只滤"最近一次上屏的同一字/词"（避免重复显示）；MRU 词组保留置顶
        // v0.5.4 反馈②：上次选中的字/词置顶须"编码匹配当前输入"（避免错位霸榜挡住四码词组）
        // ① 第一位：上次选中的字/词（固化规则）+ MRU 最近上屏词组（编码匹配）
        if (!lastSelected.isEmpty() && !isJustCommitted(lastSelected)) {
            String lc = lastSelected.length() >= 2 ? WubiDb.phraseCode(lastSelected) : WubiDb.singleCode(lastSelected);
            if (lc != null && lc.startsWith(code) && !merged.contains(lastSelected)) merged.add(lastSelected);
        }
        for (String p : recentPhrases) {
            String pc = WubiDb.phraseCode(p);
            if (pc != null && pc.startsWith(code) && !merged.contains(p)) merged.add(p);
        }
        // ② 第二位：传统五笔（高频字/字根/一至四码简码词组，优先照顾老用户习惯）
        List<String> local = WubiDb.query(code);
        if (local != null) {
            for (String c : local) {
                if (isJustCommitted(c)) continue;
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
        candidates.addAll(merged);
        candPage = 0;
        if (candidates.isEmpty()) candidates.add(code);
        updateCandidateView();
        // ④ 云端热点词组：异步回填，插到 MRU 段之后、五笔之前
        if (GATEWAY_READY) {
            cloudInsertPos = merged.size();
            queryGatewayAsync(code);
        }
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
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
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
                int pos = Math.min(Math.max(cloudInsertPos, 0), candidates.size());
                int inserted = 0;
                for (String s : result) {
                    if (isJustCommitted(s)) continue;
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

    private List<String> parseCandidates(String json) {
        List<String> list = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray cps = obj.optJSONArray("candidates");
            if (cps != null) {
                for (int i = 0; i < cps.length(); i++) {
                    int cp = cps.getInt(i);
                    if (cp > 0) list.add(new String(Character.toChars(cp)));
                }
            }
            JSONArray phrases = obj.optJSONArray("phrases");
            if (phrases != null) {
                for (int i = 0; i < phrases.length(); i++) {
                    String p = phrases.getString(i);
                    if (p != null && p.length() >= 2) list.add(p);
                }
            }
        } catch (Exception ignored) { }
        return list;
    }

    /** 候选条渲染：空闲态（云五笔 ▾ 剪贴板）/ 剪贴板历史 / 数字计算 / 候选列表 */
    private void updateCandidateView() {
        if (candidateView == null) return;
        if (!chineseMode) {
            setHintText("EN");
            return;
        }
        String code = composingCode.toString();
        // v0.4.8 反馈③：非剪贴板状态恢复默认行距
        candidateView.setLineSpacing(0f, 1.0f);
        // v0.4.8 反馈⑧：数字面板计算状态实时显示（v0.5.1 候选条提供 带式/仅结果 两种上屏）
        if (panelMode == 1 && !calcBuffer.isEmpty()) {
            renderCalcState();
            return;
        }
        // v0.5.1：数字面板空缓冲提示
        if (panelMode == 1) {
            candidateView.setText("（123）输入数字与运算符");
            return;
        }
        if (clipMode) {
            renderClipboardList();
            return;
        }
        if (code.isEmpty()) {
            // v0.4.8/0.4.9 反馈⑦①：上屏后显示连续联想词组/英文翻译提示
            if (!lastCommittedText.isEmpty() && (!candidates.isEmpty() || !lastEnHint.isEmpty())) {
                renderAssociateHint();
                return;
            }
            // v0.5.3 反馈③④⑦：空闲态仅显示"云五笔"（弱色跟随系统），剪贴板入口已移至工具栏亖
            SpannableString ss = new SpannableString("云五笔");
            ss.setSpan(new ForegroundColorSpan(dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            candidateView.setText(ss);
            return;
        }
        renderCandidates();
    }

    /** v0.4.8 数字面板：实时显示 表达式=结果 */
    /** v0.5.1 反馈②：数字面板实时计算 + 两种上屏方式可选（带运算式 / 仅结果） */
    private void renderCalcState() {
        if (calcBuffer.isEmpty()) {
            candidateView.setText("（123）输入数字与运算符");
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
        if (!Double.isNaN(v)) {
            sb.append("   ");
            appendCalcOption(sb, "〔带式〕" + calcBuffer + "=" + res, true);
            sb.append("  ");
            appendCalcOption(sb, "〔结果〕" + res, false);
        }
        candidateView.setText(sb);
    }

    /** v0.5.1 计算候选：可点击上屏（带式或仅结果） */
    private void appendCalcOption(SpannableStringBuilder sb, String text, final boolean withFormula) {
        int s = sb.length();
        sb.append(text);
        sb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                commitCalc(withFormula);
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT);
                ds.setUnderlineText(false);
            }
        }, s, s + text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** v0.4.8 反馈①⑦：上屏单字后的联想词组（MRU 置顶 + 本地含字词组 + 云端热点）与英文翻译提示 */
    /** v0.4.8/0.4.9 反馈①：连续联想候选条（MRU 置顶 + 前缀/含字联想 + 翻页 + 英文翻译提示） */
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
        } else if (!lastEnHint.isEmpty()) {
            sb.append("EN: ").append(lastEnHint);
            candidateView.setText(sb);
            return;
        } else {
            sb.append("（暂无联想，继续输入编码）");
        }
        if (!lastEnHint.isEmpty()) {
            sb.append("  EN: ").append(lastEnHint);
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
     *  v0.5.3 反馈④：2 倍行距 + 灰色下横线分隔 + 跟随系统色 */
    private void renderClipboardList() {
        candidateView.setLineSpacing(18f, 1.3f);
        int textColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        int sepColor = dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT;
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
                // v0.5.3 反馈④：条目间灰色下横线（最后一项除外）
                if (i < shown - 1) sb.append("――――――――\n");
            }
        }
        SpannableString css = new SpannableString(sb.toString());
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
            // v0.5.3 反馈④：分隔线灰色
            int sepIdx = sb.indexOf("――――――――", end);
            if (sepIdx >= 0) {
                css.setSpan(new ForegroundColorSpan(sepColor), sepIdx, sepIdx + 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        candidateView.setText(css);
    }

    /** 候选列表：v0.5.3 反馈③⑦——纯候选（去杂项）；v0.5.4 反馈④：编码前缀实时显示（含删除时同步） */
    private void renderCandidates() {
        candidateView.setLineSpacing(0f, 1.0f);
        String code = composingCode.toString();
        int total = candidates.size();
        int pages = Math.max(1, (total + CAND_PAGE_SIZE - 1) / CAND_PAGE_SIZE);
        if (candPage >= pages) candPage = pages - 1;
        int from = candPage * CAND_PAGE_SIZE;
        int to = Math.min(total, from + CAND_PAGE_SIZE);
        int textColor = dark() ? THEME_DARK_TEXT : THEME_LIGHT_TEXT;
        int encColor = dark() ? THEME_DARK_HINT : THEME_LIGHT_HINT;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        // v0.5.4 反馈④：编码实时回显（弱色小字，删除同步）
        int encStart = sb.length();
        sb.append(code).append("  ");
        sb.setSpan(new ForegroundColorSpan(encColor), encStart, encStart + code.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    /** v0.4.8 反馈④：回车——无候选时上屏换行；v0.5.3 反馈⑥：单行文本框回车无反应（不换行不空格） */
    private void commitFirstCandidate() {
        if (composingCode.length() > 0 && !candidates.isEmpty()) {
            selectCandidate(0);
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

    private void commitSpaceOrFirst() {
        if (composingCode.length() > 0 && !candidates.isEmpty()) {
            selectCandidate(0);
        } else {
            commitText(" ");
        }
    }

    /** v0.4.8 数字面板：= 或退出时上屏。带公式（默认）上屏 "1+2=3"；纯数字直接上屏 */
    /** v0.5.3 反馈②：数字面板运算符——上次结果上屏后接运算符自动续算（8 → +2 → 8+2=10） */
    private void calcAppendOp(String op) {
        if (calcBuffer.isEmpty() && !lastCalcResult.isEmpty()) {
            calcBuffer = lastCalcResult + op;
        } else {
            calcBuffer += op;
        }
        updateCandidateView();
    }

    private void commitCalc(boolean fromEq) {
        if (calcBuffer.isEmpty()) return;
        double v = calcEval(calcBuffer);
        if (Double.isNaN(v)) {
            commitText(calcBuffer);
            lastCalcResult = "";
        } else {
            String res = fmtResult(v);
            lastCalcResult = res;   // v0.5.3 反馈②：记住结果供接续计算
            if (calcBuffer.matches("^[0-9.]+$")) {
                commitText(calcBuffer);
            } else if (calcFormula) {
                // v0.5.5 反馈③：续算带式去重——表达式以"上次结果"开头时省略重复结果（2+5=7 上屏后 -4= 上屏"-4=3"）
                String expr = calcBuffer;
                if (expr.startsWith(lastCalcResult)) {
                    expr = expr.substring(lastCalcResult.length());
                    if (expr.isEmpty()) expr = res;
                }
                commitText(expr + "=" + res);
            } else {
                commitText(res);
            }
        }
        calcBuffer = "";
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
        // v0.5.3 反馈①：记录最近一次上屏的字/词（仅滤紧接着的重复出现）
        committedLast = text;
        if (GATEWAY_READY) reportSelection(text);
        composingCode.setLength(0);
        candidates.clear();
        // v0.4.9 反馈①：连续联想——联想态选词拼接成链（陈+胜=陈胜），普通输入重置
        if (associateActive) {
            lastCommittedText += text;
        } else {
            lastCommittedText = text;
        }
        if (text.length() >= 2) rememberPhrase(text);   // MRU（最近 3 词组）
        lastEnHint = "";
        // v0.5.5 反馈①：密码框禁联想/翻译（隐私 + 避免干扰输入）
        if (isPassword) {
            associateActive = false;
            updateCandidateView();
            return;
        }
        String lastChar = lastCommittedText.isEmpty() ? "" : lastCommittedText.substring(lastCommittedText.length() - 1);
        if (!lastChar.isEmpty()) {
            associateActive = true;
            triggerAssociate();
            queryTranslation(lastChar);
        } else {
            associateActive = false;
        }
        updateCandidateView();
    }

    /** v0.4.9 反馈① + v0.5.1 反馈⑤：联想基准=整个上屏词组（如"前进"→"前进浪潮/前进号角"），
     *  不再按单字含字联想（避免"驶进/共进"式不合理联想）；MRU 置顶 + 整词前缀 + 云端 */
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
        // ②b v0.5.4 反馈⑧：锚字含字词组兜底（我们→你们/他们/咱们；陈胜→陈胜吴广）
        if (merged.size() < 12 && !anchor.isEmpty()) {
            List<String> byChar = WubiDb.queryByChar(anchor);
            if (byChar != null) {
                for (String p : byChar) {
                    if (!merged.contains(p)) merged.add(p);
                    if (merged.size() >= 12) break;
                }
            }
        }
        candidates.clear();
        candidates.addAll(merged);
        candPage = 0;
        updateCandidateView();
        // ③ 云端热点：整词前缀通道
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

    /** v0.4.9：云端连续联想（{"prefix":"陈胜"} 前缀热点 + {"word":"陈"} 含字热点） */
    private void queryAssociateAsync(final String chain, final String lastChar) {
        final Handler handler = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            List<String> cloud = new ArrayList<>();
            try {
                // 前缀通道（v0.5.1 反馈⑤：整词联想基准，只用整词前缀；含字通道已停用）
                List<String> p1 = postGateway("{\"prefix\":\"" + chain + "\"}");
                if (p1 != null) cloud.addAll(p1);
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

    /** 云端 POST 请求（返回 phrases 数组，失败返回 null） */
    private List<String> postGateway(String body) {
        List<String> cloud = new ArrayList<>();
        try {
            URL url = new URL(GATEWAY_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
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
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
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
