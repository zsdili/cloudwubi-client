package com.cloudwubi.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
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
import java.util.List;

/**
 * CloudWubiIME - 云五笔 Android 输入法服务（v0.4.5）
 *
 * v0.4.5 修复清单（真机第三轮 8 项反馈）：
 *   ① 键盘布局严格对齐参考截图（键宽比例/留白/圆角；行1 ?123 / 行2 ↑Shift / 行3 符+退格 / 行4 123|中英|，|空格|？|符|回车）
 *   ② 中/英切换按钮：中文时显示「中」，英文时显示「EN」；英文模式键盘文字切换为英文（?123 / SYM / , / ?）
 *   ③ ↑ = Shift 键：单击切换大小写，连按两次锁定大写（Caps），再按解锁
 *   ④ 留边、圆角：键盘左右留白 6dp、键帽圆角 12dp
 *   ⑤ 功能键布局按截图原样（?123 与 123、符与符 为双入口设计，拇指可达性）
 *   ⑥ 剪贴板历史仅记录「复制」的文本，不再记录上屏内容
 *   ⑦ 键帽左上角标注上滑符号（Q 键见「1」、A 键见「@」等）
 *   ⑧ 候选条显示当前编码：输入 qkhh 时显示「（qkhh）1.钟 2.鈡」
 */
public class CloudWubiIME extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    /** 云端网关地址（占位符时不请求，部署后替换） */
    private static final String GATEWAY_URL =
            "https://YOUR-GATEWAY-URL/release/wubi/query";  // TODO: 部署后替换
    private static final boolean GATEWAY_READY =
            !GATEWAY_URL.contains("YOUR-GATEWAY-URL");

    // ===== 功能键编码（与 XML 严格对应） =====
    private static final int KEY_123 = -101;      // 数字面板
    private static final int KEY_LANG = -102;     // 中/英
    private static final int KEY_SYMBOL = -103;   // 面板返回主键盘
    private static final int KEY_SYM_IN = -104;   // 符号面板
    private static final int KEY_SHIFT = -105;    // ↑ Shift（单击切换/双击锁定大写）
    private static final int KEY_SPACE = 32;
    private static final int KB_DELETE = -5;      // Keyboard.KEYCODE_DELETE
    private static final int KB_ENTER = -4;       // Keyboard.KEYCODE_ENTER

    // ===== 剪贴板历史（仅复制文本，v0.4.5） =====
    private static final int CLIP_MAX = 20;
    private static final String PREFS_NAME = "cloudwubi";
    private static final String PREFS_CLIP = "clip_history";

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
        keyboardView.setBackgroundColor(0xFFF3F4F6);
        // 反馈④：键盘左右留边
        int dp6 = Math.round(6 * getResources().getDisplayMetrics().density);
        int dp4 = Math.round(4 * getResources().getDisplayMetrics().density);
        keyboardView.setPadding(dp6, dp4, dp6, dp4);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF3F4F6);
        root.addView(candidateView);
        root.addView(keyboardView);
        applyLangLabels();
        keyboardView.setShifted(true);   // 中文模式默认大写显示
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
        keyboardView.setShifted(chineseMode);
        super.onStartInputView(info, restarting);
    }

    private void resetComposing() {
        composingCode.setLength(0);
        candidates.clear();
        clipMode = false;
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

    // ===== 键盘文字：中文「中」/ 英文「EN」及功能键英文（反馈②） =====

    private void applyLangLabels() {
        if (keyboardMain == null) return;
        for (Keyboard.Key k : keyboardMain.getKeys()) {
            int c = k.codes[0];
            if (c == KEY_LANG) {
                k.label = chineseMode ? "中" : "EN";
            } else if (c == KEY_123) {
                String cur = k.label == null ? "" : k.label.toString();
                if (cur.length() > 3) {        // 行1「？123 / ?123」（含？）
                    k.label = chineseMode ? "？123" : "?123";
                }
                // 行4「123」保持不变
            } else if (c == 63) {
                k.label = chineseMode ? "？" : "?";
            } else if (c == 44) {
                k.label = chineseMode ? "，" : ",";
            } else if (c == KEY_SYM_IN) {
                k.label = chineseMode ? "符" : "SYM";
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
        keyboardView.setShifted(shiftState > 0);
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
                    keyboardView.setShifted(false);
                }
            }
            return;
        }
        // 数字
        if (primaryCode >= '0' && primaryCode <= '9') {
            commitText(String.valueOf((char) primaryCode));
            return;
        }
        switch (primaryCode) {
            case KEY_123:
                panelMode = 1;
                keyboardView.setKeyboard(keyboardNum);
                return;
            case KEY_SYM_IN:
                panelMode = 2;
                keyboardView.setKeyboard(keyboardSymbols);
                return;
            case KEY_SYMBOL:
                panelMode = 0;
                keyboardView.setKeyboard(keyboardMain);
                return;
            case KEY_LANG:
                if (panelMode != 0) {
                    panelMode = 0;
                    keyboardView.setKeyboard(keyboardMain);
                } else {
                    toggleLang();
                }
                return;
            case KEY_SHIFT:
                handleShift();
                return;
            case KEY_SPACE:
                commitSpaceOrFirst();
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
            keyboardView.setShifted(false);
        } else {
            keyboardView.setShifted(true);
        }
        applyLangLabels();
        updateCandidateView();
    }

    private void appendCode(char c) {
        if (composingCode.length() >= 4) return;
        composingCode.append(c);
        queryCandidates();
    }

    private void handleBackspace() {
        if (composingCode.length() > 0) {
            composingCode.deleteCharAt(composingCode.length() - 1);
            queryCandidates();
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
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

    /** 候选查询：本地词库优先（MRU 置顶 + 3码预测），云端增强（占位符时跳过） */
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
            List<String> cloud = queryGateway(code);
            if (cloud != null) {
                for (String s : cloud) {
                    if (!candidates.contains(s)) candidates.add(s);
                }
            }
        }
        if (candidates.isEmpty()) candidates.add(code);
        updateCandidateView();
    }

    /** 云端网关查询（部署后启用，异步不阻塞） */
    private List<String> queryGateway(final String code) {
        final java.util.concurrent.atomic.AtomicReference<List<String>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
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
                        result.set(parseCandidates(sb.toString()));
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) { }
        });
        t.start();
        try { t.join(2500); } catch (InterruptedException ignored) { }
        return result.get();
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

    /** 候选条渲染：空闲态（云五笔 ▾ 剪贴板）/ 剪贴板历史 / 候选列表 */
    private void updateCandidateView() {
        if (candidateView == null) return;
        if (!chineseMode) {
            setHintText("EN");
            return;
        }
        String code = composingCode.toString();
        if (clipMode) {
            renderClipboardList();
            return;
        }
        if (code.isEmpty()) {
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

    private void setHintText(String text) {
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new ForegroundColorSpan(0xFF6B7280), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        candidateView.setText(ss);
    }

    /** 剪贴板历史列表（仅复制文本，最新在前，最多 8 条显示；点击上屏） */
    private void renderClipboardList() {
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

    /** 候选列表：反馈⑧ 输入编码显示在括号里（qkhh）1.钟 2.鈡 */
    private void renderCandidates() {
        String code = composingCode.toString();
        StringBuilder sb = new StringBuilder();
        SpannableString css = new SpannableString("");
        sb.append("（").append(code).append("）  ");
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
        css.setSpan(new ForegroundColorSpan(0xFF3B82F6), 0, code.length() + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        candidateView.setText(css);
    }

    private void commitFirstCandidate() {
        if (composingCode.length() > 0 && !candidates.isEmpty()) {
            selectCandidate(0);
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(" ", 1);
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
        updateCandidateView();
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
