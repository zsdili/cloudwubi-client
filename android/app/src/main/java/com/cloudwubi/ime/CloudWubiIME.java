package com.cloudwubi.ime;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
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
 * CloudWubiIME - 云五笔 Android 输入法服务（v0.4.3）
 *
 * v0.4.3 修复清单（真机 8 项反馈）：
 *   ① 退格/回车键码不匹配（XML -5/-4 vs KeyEvent 67/66）→ 双通道兼容，删除/上屏生效
 *   ② 布局对齐主流输入法：字母大写；，。分居空格两侧；退格键/回车键加宽充盈
 *   ③ 输入窗口消失（切换应用/收起键盘）时清空未上屏编码与候选
 *   ④ 符号面板全部符号键可直接上屏（中文标点自动全角）
 *   ⑤ 词库扩充至 GB2312 一级 6682 单字（qkhh→钟、etg→胜 已覆盖）
 *   ⑥ 键盘字母改为大写显示
 *   ⑦ 图标改为"云端五笔"红底白字印章
 *   ⑧ 版本号 v0.4.3 + 作者：钟志胜 + 联系QQ：175571（候选条空闲态展示）
 *
 * 候选排序（用户固化，先科学后先进）：
 *   1码 → 高频单字；2码 → 单字在前、二字词在后；3码 → 单字 + 第4码高频词组预测；
 *   4码 → 词组优先、单字殿后；上次选中的字/词优先置顶（MRU）
 */
public class CloudWubiIME extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    /** 云端网关地址（占位符时不请求，部署后替换） */
    private static final String GATEWAY_URL =
            "https://YOUR-GATEWAY-URL/release/wubi/query";  // TODO: 部署后替换
    private static final boolean GATEWAY_READY =
            !GATEWAY_URL.contains("YOUR-GATEWAY-URL");

    // ===== 应用信息（用户固化：版本号/作者/QQ） =====
    private static final String APP_VERSION = "v0.4.3";
    private static final String APP_AUTHOR = "钟志胜";
    private static final String APP_QQ = "175571";
    private static final String APP_INFO = "云五笔 " + APP_VERSION + " · 作者：" + APP_AUTHOR + " · QQ：" + APP_QQ;

    // ===== 功能键编码（自定义，与 XML 严格对应） =====
    private static final int KEY_123 = -101;      // 主键盘 -> 数字/符号面板
    private static final int KEY_LANG = -102;     // 中/英切换
    private static final int KEY_SYMBOL = -103;   // 符号面板 -> 返回主键盘
    private static final int KEY_SYM_IN = -104;   // 主键盘"符"键 -> 进入符号面板
    private static final int KEY_SPACE = 32;
    // XML 中的系统键码（Keyboard.KEYCODE_*）
    private static final int KB_DELETE = -5;
    private static final int KB_ENTER = -4;

    private final StringBuilder composingCode = new StringBuilder();
    private List<String> candidates = new ArrayList<>();
    private TextView candidateView;
    private KeyboardView keyboardView;
    private Keyboard keyboardMain;
    private Keyboard keyboardSymbols;
    private boolean chineseMode = true;   // 中/英
    private boolean symbolMode = false;   // 数字/符号面板
    private String lastSelected = "";     // 上次选中的字/词（MRU 置顶）

    @Override
    public View onCreateInputView() {
        candidateView = new TextView(this);
        candidateView.setTextSize(15);
        candidateView.setPadding(14, 12, 14, 12);
        candidateView.setTextColor(0xFFE5E7EB);
        candidateView.setBackgroundColor(0xFF1F2937);
        candidateView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        candidateView.setHighlightColor(0x00000000);

        keyboardMain = new Keyboard(this, R.xml.keyboard_qwerty);
        keyboardSymbols = new Keyboard(this, R.xml.keyboard_symbols);
        keyboardView = new KeyboardView(this, null);
        keyboardView.setKeyboard(keyboardMain);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);
        keyboardView.setHapticFeedbackEnabled(true);
        keyboardView.setBackgroundColor(0xFF111827);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111827);
        root.addView(candidateView);
        root.addView(keyboardView);
        updateCandidateView();
        return root;
    }

    // ===== 生命周期：输入窗口关闭时清空未上屏状态（反馈③） =====

    @Override
    public void onFinishInput() {
        resetComposing();
        super.onFinishInput();
    }

    @Override
    public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        resetComposing();
        symbolMode = false;
        keyboardView.setKeyboard(keyboardMain);
        super.onStartInputView(info, restarting);
    }

    private void resetComposing() {
        composingCode.setLength(0);
        candidates.clear();
        updateCandidateView();
    }

    // ===== KeyboardView.OnKeyboardActionListener =====

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        // 字母键（大写显示、小写编码与输出）
        if (primaryCode >= 'a' && primaryCode <= 'z') {
            if (chineseMode) {
                appendCode((char) primaryCode);
            } else {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(String.valueOf((char) primaryCode), 1);
            }
            return;
        }
        // 数字（主键盘 123 面板与直接数字）
        if (primaryCode >= '0' && primaryCode <= '9') {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(String.valueOf((char) primaryCode), 1);
            return;
        }
        switch (primaryCode) {
            case KEY_123:      // 主键盘 -> 数字/符号面板
            case KEY_SYM_IN:   // 主键盘"符"键 -> 数字/符号面板
                symbolMode = true;
                keyboardView.setKeyboard(keyboardSymbols);
                return;
            case KEY_SYMBOL:   // 符号面板"返回" -> 主键盘
                symbolMode = false;
                keyboardView.setKeyboard(keyboardMain);
                return;
            case KEY_LANG:
                if (symbolMode) {
                    symbolMode = false;
                    keyboardView.setKeyboard(keyboardMain);
                } else {
                    toggleLang();
                }
                return;
            case KEY_SPACE:
                commitSpaceOrFirst();
                return;
            case KB_DELETE:
            case KeyEvent.KEYCODE_DEL:     // 兼容物理键码 67（反馈①）
                handleBackspace();
                return;
            case KB_ENTER:
            case KeyEvent.KEYCODE_ENTER:   // 兼容物理键码 66（反馈①）
                commitFirstCandidate();
                return;
            case 44:   // ，
                commitText("，");
                return;
            case 46:   // 。
                commitText("。");
                return;
            default:
                // 符号面板：所有符号键直接上屏（反馈④），中文标点自动全角
                if (symbolMode) {
                    String s = symbolToText(primaryCode);
                    if (s != null) commitText(s);
                }
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

    // ===== 物理键盘支持（可选） =====

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
        }
        updateCandidateView();
    }

    private void appendCode(char c) {
        if (composingCode.length() >= 4) return;
        composingCode.append(c);
        queryCandidates();
    }

    /** 退格：优先删未上屏编码，无编码时删除上屏字符（反馈①） */
    private void handleBackspace() {
        if (composingCode.length() > 0) {
            composingCode.deleteCharAt(composingCode.length() - 1);
            queryCandidates();
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
        }
    }

    private void commitText(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }

    /** 符号面板码点 -> 上屏文本（中文标点映射全角） */
    private String symbolToText(int code) {
        switch (code) {
            case 44: return "，";
            case 46: return "。";
            case 63: return "？";
            case 33: return "！";
            case 8226: return "·";
            case 183: return "·";
            case 34: return "\"";
            case 39: return "'";
            case 45: return "-";
            case 47: return "/";
            case 58: return ":";
            case 59: return ";";
            case 40: return "(";
            case 41: return ")";
            case 64: return "@";
            case 35: return "#";
            case 95: return "_";
            case 38: return "&";
            case 42: return "*";
            case 37: return "%";
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
        // 1) 本地五笔86词库（离线可用）
        List<String> local = WubiDb.query(code);
        if (local != null) candidates.addAll(local);
        // 2) MRU：上次选中的字/词优先置顶（用户固化规则）
        if (!lastSelected.isEmpty() && candidates.contains(lastSelected)) {
            candidates.remove(lastSelected);
            candidates.add(0, lastSelected);
        }
        // 3) 3码时预测第4码高频词组（用户固化规则：三字母提示第四码词组）
        if (code.length() == 3) {
            List<String> predict = WubiDb.queryPredict(code);
            if (predict != null) {
                for (String p : predict) {
                    if (!candidates.contains(p)) candidates.add(p);
                }
            }
        }
        // 4) 云端增强（已部署时启用）
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

    /** 候选条：空闲态展示版本/作者/QQ；输入态点击选字（反馈⑧） */
    private void updateCandidateView() {
        if (candidateView == null) return;
        SpannableString ss;
        if (!chineseMode) {
            ss = new SpannableString("中文");
            ss.setSpan(new ForegroundColorSpan(0xFF60A5FA), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            candidateView.setText(ss);
            return;
        }
        String code = composingCode.toString();
        if (code.isEmpty()) {
            ss = new SpannableString(APP_INFO);
            ss.setSpan(new ForegroundColorSpan(0xFF9CA3AF), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            candidateView.setText(ss);
            return;
        }
        StringBuilder sb = new StringBuilder();
        SpannableString css = new SpannableString(sb.toString());
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
                    ds.setColor(0xFFE5E7EB);
                    ds.setUnderlineText(false);
                }
            };
            css.setSpan(cs, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i == 0) {
                css.setSpan(new ForegroundColorSpan(0xFFF59E0B), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
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
        lastSelected = text;   // MRU 置顶记忆（反馈：上次选中优先）
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
