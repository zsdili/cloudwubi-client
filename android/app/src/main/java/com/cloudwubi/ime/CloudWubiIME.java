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
    private static final int KEY_SHIFT = -105;    // ↑ Shift（单击切换/双击锁定大写）
    private static final int KEY_PUNCT_BANG = -106; // !，双标点循环
    private static final int KEY_MIC = -107;      // v0.4.8: 空格键（原🎤语音占位已移除）
    private static final int KEY_PUNCT_QM = -108; // ？。双标点循环
    private static final int KEY_CALC_EQ = -201;  // 数字面板 = 计算上屏
    private static final int KEY_CALC_DIV = -202; // 数字面板 ÷
    private static final int KEY_CALC_MUL = -203; // 数字面板 ×
    private static final int KEY_SPACE = 32;
    private static final int KB_DELETE = -5;      // Keyboard.KEYCODE_DELETE
    private static final int KB_ENTER = -4;       // Keyboard.KEYCODE_ENTER

    // ===== 剪贴板历史（仅复制文本，v0.4.5） =====
    private static final int CLIP_MAX = 20;
    private static final String PREFS_NAME = "cloudwubi";
    private static final String PREFS_CLIP = "clip_history";
    private static final String PREFS_PHRASES = "recent_phrases";  // v0.4.8 MRU 词组

    private final StringBuilder composingCode = new StringBuilder();
    private List<String> candidates = new ArrayList<>();
    private TextView candidateView;
    private CloudKeyboardView keyboardView;
    private Keyboard keyboardMain;
    private Keyboard keyboardNum;
    private Keyboard keyboardSymbols;
    private boolean chineseMode = true;
    private int panelMode = 0;              // 0=主键盘 1=数字 2=符号
    private boolean clipMode = false;       // 候选条是否显示剪贴板历史
    private String lastSelected = "";       // MRU 置顶
    private String lastCommittedChar = "";  // v0.4.8 最近上屏单字（触发联想）
    private String lastEnHint = "";         // v0.4.8 最近选字英文翻译提示
    private List<String> recentPhrases = new ArrayList<>();  // v0.4.8 最近3个选中词组
    private String calcBuffer = "";         // v0.4.8 数字面板计算表达式
    private boolean calcFormula = true;     // v0.4.8 默认带公式上屏（长按=仅结果）

    // Shift / Caps（反馈③）
    private int shiftState = 0;             // 0=小写 1=单次大写 2=锁定大写
    private long lastShiftTap = 0L;

    // 双标点循环（v0.4.6：！，/ ？。 键）
    private boolean bangFirst = true;       // !，键当前输出 ！（再点切 ，）
    private boolean qmFirst = true;         // ？。键当前输出 ？（再点切 。）

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
        candidateView.setTextColor(0xFF1F2937);
        candidateView.setBackgroundColor(0xFFFFFFFF);
        candidateView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        candidateView.setHighlightColor(0x00000000);

        keyboardMain = new Keyboard(this, R.xml.keyboard_qwerty);
        keyboardNum = new Keyboard(this, R.xml.keyboard_num);
        keyboardSymbols = new Keyboard(this, R.xml.keyboard_sym);
        keyboardView = new CloudKeyboardView(this, null);
        keyboardView.setKeyboard(keyboardMain);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);
        keyboardView.setHapticFeedbackEnabled(true);
        keyboardView.setBackgroundColor(0xFFE8EBEF);   // 键盘底色对齐截图
        // 反馈④：键盘左右留边（截图约 4.5% 屏宽）
        int dp12 = Math.round(12 * getResources().getDisplayMetrics().density);
        int dp6 = Math.round(6 * getResources().getDisplayMetrics().density);
        keyboardView.setPadding(dp12, dp6, dp12, dp6);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFE8EBEF);
        root.addView(candidateView);
        root.addView(keyboardView);
        applyLangLabels();
        applyLetterCase();   // 中文模式默认大写显示
        updateCandidateView();
        return root;
    }

    // ===== 生命周期 =====

    @Override
    public void onFinishInput() {
        resetComposing();
        super.onFinishInput();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        resetComposing();
        panelMode = 0;
        clipMode = false;
        keyboardView.setKeyboard(keyboardMain);
        applyLetterCase();
        super.onStartInputView(info, restarting);
    }

    private void resetComposing() {
        composingCode.setLength(0);
        candidates.clear();
        clipMode = false;
        lastCommittedChar = "";
        lastEnHint = "";
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
                k.label = chineseMode ? "！，" : "!,";
            } else if (c == KEY_PUNCT_QM) {
                k.label = chineseMode ? "？。" : "?.";
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

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        // 字母键
        if (primaryCode >= 'a' && primaryCode <= 'z') {
            if (chineseMode) {
                appendCode((char) primaryCode);
            } else {
                char out = (shiftState > 0) ? Character.toUpperCase((char) primaryCode) : (char) primaryCode;
                commitText(String.valueOf(out));
                if (shiftState == 1) {          // 单次大写后复位
                    shiftState = 0;
                    applyLetterCase();
                }
            }
            return;
        }
        // 数字（v0.4.8：数字面板进入计算缓冲，其余直接上屏）
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
                panelMode = 2;
                keyboardView.setKeyboard(keyboardSymbols);
                return;
            case KEY_SYMBOL:   // 面板返回主键盘（v0.4.8：数字面板有表达式时先确认上屏）
                if (panelMode == 1 && !calcBuffer.isEmpty()) commitCalc(false);
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
            case KEY_PUNCT_BANG:   // ！，双标点循环
                commitText(bangFirst ? "！" : "，");
                bangFirst = !bangFirst;
                return;
            case KEY_PUNCT_QM:     // ？。双标点循环
                commitText(qmFirst ? "？" : "。");
                qmFirst = !qmFirst;
                return;
            case KEY_MIC:          // v0.4.8：空格键（原 🎤 语音占位已移除）
                commitSpaceOrFirst();
                return;
            case KEY_SPACE:
                commitSpaceOrFirst();
                return;
            case KEY_CALC_EQ:      // = 计算上屏（默认带公式；纯数字直接上屏数字）
                commitCalc(true);
                return;
            case KEY_CALC_DIV:     // ÷
                if (panelMode == 1) { calcBuffer += "÷"; updateCandidateView(); }
                return;
            case KEY_CALC_MUL:     // ×
                if (panelMode == 1) { calcBuffer += "×"; updateCandidateView(); }
                return;
            case 43:               // +
                if (panelMode == 1) { calcBuffer += "+"; updateCandidateView(); }
                return;
            case 45:               // -
                if (panelMode == 1) { calcBuffer += "-"; updateCandidateView(); }
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
                commitText("，");
                return;
            case 46:
                commitText("。");
                return;
            default:
                String s = symbolToText(primaryCode);
                if (s != null) commitText(s);
        }
    }

    @Override
    public void onPress(int primaryCode) { }

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
    public void swipeUp() { }

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
            lastEnHint = "";
        }
        applyLetterCase();
        applyLangLabels();
        updateCandidateView();
    }

    private void appendCode(char c) {
        if (composingCode.length() >= 4) return;
        composingCode.append(c);
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
            if (sel != null && sel.length() > 0) {
                ic.commitText("", 0);
            } else {
                ic.deleteSurroundingText(1, 0);
            }
        }
    }

    /** 上屏（v0.4.5：不再写入剪贴板历史，仅系统复制触发历史） */
    private void commitText(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
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

    /** 候选查询：本地词库优先（MRU 置顶 + 3码预测），云端增强（异步回填，不阻塞主线程） */
    private void queryCandidates() {
        candidates.clear();
        String code = composingCode.toString();
        if (code.isEmpty()) {
            updateCandidateView();
            return;
        }
        List<String> local = WubiDb.query(code);
        if (local != null) candidates.addAll(local);
        if (!lastSelected.isEmpty() && candidates.contains(lastSelected)) {
            candidates.remove(lastSelected);
            candidates.add(0, lastSelected);
        }
        if (code.length() == 3) {
            List<String> predict = WubiDb.queryPredict(code);
            if (predict != null) {
                for (String p : predict) {
                    if (!candidates.contains(p)) candidates.add(p);
                }
            }
        }
        if (GATEWAY_READY) {
            queryGatewayAsync(code);   // 后台线程查询，主线程回填
        }
        if (candidates.isEmpty()) candidates.add(code);
        updateCandidateView();
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
                boolean changed = false;
                for (String s : result) {
                    if (!candidates.contains(s)) {
                        candidates.add(s);
                        changed = true;
                    }
                }
                if (changed) updateCandidateView();
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
        // v0.4.8 反馈⑧：数字面板计算状态实时显示 表达式=结果
        if (panelMode == 1) {
            renderCalcState();
            return;
        }
        if (clipMode) {
            renderClipboardList();
            return;
        }
        if (code.isEmpty()) {
            // v0.4.8 反馈⑦：上屏单字后显示联想词组/英文翻译提示
            if (!lastCommittedChar.isEmpty() && (!candidates.isEmpty() || !lastEnHint.isEmpty())) {
                renderAssociateHint();
                return;
            }
            SpannableString ss = new SpannableString("云五笔   ▾ 剪贴板");
            ss.setSpan(new ForegroundColorSpan(0xFF9CA3AF), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int s = 6, e = ss.length();
            ClickableSpan cs = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    clipMode = true;
                    updateCandidateView();
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(0xFF3B82F6);
                    ds.setUnderlineText(false);
                }
            };
            ss.setSpan(cs, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            candidateView.setText(ss);
            return;
        }
        renderCandidates();
    }

    /** v0.4.8 数字面板：实时显示 表达式=结果 */
    private void renderCalcState() {
        if (calcBuffer.isEmpty()) {
            candidateView.setText("（123）输入数字与运算符，= 上屏");
            return;
        }
        double v = calcEval(calcBuffer);
        String res = fmtResult(v);
        SpannableString ss = new SpannableString(calcBuffer + " = " + res + "   ▸ 按=上屏·空格仅结果");
        ss.setSpan(new ForegroundColorSpan(0xFF3B82F6), 0, calcBuffer.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (res.equals("错误")) {
            ss.setSpan(new ForegroundColorSpan(0xFFDC2626), calcBuffer.length() + 3, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        candidateView.setText(ss);
    }

    /** v0.4.8 反馈①⑦：上屏单字后的联想词组（MRU 置顶 + 本地含字词组 + 云端热点）与英文翻译提示 */
    private void renderAssociateHint() {
        StringBuilder sb = new StringBuilder();
        sb.append(lastCommittedChar).append(" ▸ ");
        if (!candidates.isEmpty()) {
            int shown = Math.min(8, candidates.size());
            for (int i = 0; i < shown; i++) {
                String c = candidates.get(i);
                int s = sb.length();
                sb.append(i + 1).append(".").append(c).append("  ");
                int e = sb.length();
                final int idx = i;
                SpannableString css = new SpannableString(sb.toString());
                ClickableSpan cs = new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        selectCandidate(idx);
                    }
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        ds.setColor(0xFF1F2937);
                        ds.setUnderlineText(false);
                    }
                };
                css.setSpan(cs, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (i == 0) css.setSpan(new ForegroundColorSpan(0xFFF59E0B), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else if (!lastEnHint.isEmpty()) {
            sb.append("EN: ").append(lastEnHint);
            candidateView.setText(sb.toString());
            return;
        } else {
            sb.append("（暂无联想，继续输入编码）");
        }
        if (!lastEnHint.isEmpty()) {
            sb.append("  EN: ").append(lastEnHint);
        }
        candidateView.setText(sb.toString());
    }

    private void setHintText(String text) {
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new ForegroundColorSpan(0xFF6B7280), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        candidateView.setText(ss);
    }

    /** 剪贴板历史列表（仅复制文本，最新在前，最多 8 条显示；点击上屏） */
    private void renderClipboardList() {
        // v0.4.8 反馈③：行间距加大（8dp 附加行距），易点选
        candidateView.setLineSpacing(10f, 1.0f);
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
        SpannableString css = new SpannableString(sb.toString());
        ClickableSpan backCs = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                clipMode = false;
                updateCandidateView();
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(0xFF3B82F6);
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
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.commitText(item, 1);
                    clipMode = false;
                    updateCandidateView();
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(0xFF1F2937);
                    ds.setUnderlineText(false);
                }
            };
            css.setSpan(cs, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        candidateView.setText(css);
    }

    /** 候选列表：v0.4.8 反馈⑦ 编码括号移到候选末尾：1.钟 2.鈡（qkhh） */
    private void renderCandidates() {
        candidateView.setLineSpacing(0f, 1.0f);
        String code = composingCode.toString();
        StringBuilder sb = new StringBuilder();
        SpannableString css = new SpannableString("");
        for (int i = 0; i < candidates.size() && i < 10; i++) {
            String c = candidates.get(i);
            int s = sb.length();
            sb.append(i + 1).append(".").append(c).append("  ");
            int e = sb.length();
            css = new SpannableString(sb.toString());
            final int idx = i;
            ClickableSpan cs = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    selectCandidate(idx);
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(0xFF1F2937);
                    ds.setUnderlineText(false);
                }
            };
            css.setSpan(cs, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i == 0) {
                css.setSpan(new ForegroundColorSpan(0xFFF59E0B), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        // 编码括号置于末尾（v0.4.8 反馈⑦）
        int encStart = sb.length();
        sb.append("（").append(code).append("）");
        css = new SpannableString(sb.toString());
        css.setSpan(new ForegroundColorSpan(0xFF3B82F6), encStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        candidateView.setText(css);
    }

    /** v0.4.8 反馈④：回车——无候选时上屏换行（此前误上屏空格） */
    private void commitFirstCandidate() {
        if (composingCode.length() > 0 && !candidates.isEmpty()) {
            selectCandidate(0);
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText("\n", 1);
        }
    }

    private void commitSpaceOrFirst() {
        if (composingCode.length() > 0 && !candidates.isEmpty()) {
            selectCandidate(0);
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(" ", 1);
        }
    }

    /** v0.4.8 数字面板：= 或退出时上屏。带公式（默认）上屏 "1+2=3"；纯数字直接上屏 */
    private void commitCalc(boolean fromEq) {
        if (calcBuffer.isEmpty()) return;
        double v = calcEval(calcBuffer);
        if (Double.isNaN(v)) {
            commitText(calcBuffer);
        } else {
            String res = fmtResult(v);
            if (calcBuffer.matches("^[0-9.]+$")) {
                commitText(calcBuffer);
            } else if (calcFormula) {
                commitText(calcBuffer + "=" + res);
            } else {
                commitText(res);
            }
        }
        calcBuffer = "";
        panelMode = 0;
        keyboardView.setKeyboard(keyboardMain);
        updateCandidateView();
    }

    private void selectCandidate(int idx) {
        if (idx < 0 || idx >= candidates.size()) return;
        String text = candidates.get(idx);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
        lastSelected = text;   // MRU 置顶
        if (GATEWAY_READY) reportSelection(text);
        composingCode.setLength(0);
        candidates.clear();
        // v0.4.8 反馈①：上屏单字 → 自动联想（最近3词组置顶 + 本地含字词组 + 云端热点），并提示英文翻译
        if (text.length() == 1) {
            lastCommittedChar = text;
            lastEnHint = "";
            triggerAssociate(text);
            queryTranslation(text);
        } else {
            lastCommittedChar = "";
            lastEnHint = "";
            rememberPhrase(text);
        }
        updateCandidateView();
    }

    /** v0.4.8 反馈①：字后联想——MRU 最近 3 词组置顶 + 本地含字词组 + 云端热点（异步回填） */
    private void triggerAssociate(final String ch) {
        List<String> merged = new ArrayList<>();
        for (String p : recentPhrases) {           // 最近选中的词组（含此字优先）
            if (p.indexOf(ch) >= 0 && !merged.contains(p)) merged.add(p);
        }
        List<String> local = WubiDb.queryByChar(ch);
        if (local != null) {
            for (String p : local) if (!merged.contains(p)) merged.add(p);
        }
        candidates.clear();
        candidates.addAll(merged);
        updateCandidateView();
        if (GATEWAY_READY) queryAssociateAsync(ch);   // 云端热点词回填
    }

    /** v0.4.8：云端热点联想（{"word":"钟"} → phrases 含此字的词组） */
    private void queryAssociateAsync(final String word) {
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
                String body = "{\"word\":\"" + word + "\"}";
                try (OutputStream os = conn.getOutputStream()) os.write(body.getBytes("UTF-8"));
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
            final List<String> result = cloud;
            handler.post(() -> {
                if (!word.equals(lastCommittedChar)) return;
                boolean changed = false;
                for (String s : result) {
                    if (!candidates.contains(s)) { candidates.add(s); changed = true; }
                }
                if (changed) updateCandidateView();
            });
        });
        t.start();
    }

    /** v0.4.8 反馈⑦：选字后云端英文翻译（{"word":"钟","en":true} → "bell; clock"） */
    private void queryTranslation(final String word) {
        final Handler handler = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            String en = "";
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                String body = "{\"word\":\"" + word + "\",\"en\":true}";
                try (OutputStream os = conn.getOutputStream()) os.write(body.getBytes("UTF-8"));
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        en = new JSONObject(sb.toString()).optString("en", "");
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) { }
            final String hint = en;
            handler.post(() -> {
                if (!word.equals(lastCommittedChar)) return;
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
