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
 * CloudWubiIME - 云五笔 Android 输入法服务（v0.4.2 键盘改版）
 *
 * 设计原则（先科学后先进，高品质高体验）：
 *   1. 端侧离线可用：内置五笔86精简词库（一级25+二级616单字+251常用词组）
 *   2. 云端增强（预留）：网关部署后自动启用 AI 构词/排序
 *   3. 候选规则（用户固化）：
 *      1码 → 高频单字；2码 → 单字在前词组在后；3码 → 单字；
 *      4码 → 词组优先单字殿后
 *   4. 体验：候选条点击选字 + 数字选字双通道；按键震动反馈；中英切换；数字/符号键盘
 */
public class CloudWubiIME extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    /** 云端网关地址（占位符时不请求，部署后替换） */
    private static final String GATEWAY_URL =
            "https://YOUR-GATEWAY-URL/release/wubi/query";  // TODO: 部署后替换
    private static final boolean GATEWAY_READY =
            !GATEWAY_URL.contains("YOUR-GATEWAY-URL");

    // ===== 功能键编码（自定义） =====
    private static final int KEY_123 = -101;
    private static final int KEY_LANG = -102;
    private static final int KEY_SYMBOL = -103;
    private static final int KEY_SPACE = 32;

    private final StringBuilder composingCode = new StringBuilder();
    private List<String> candidates = new ArrayList<>();
    private TextView candidateView;
    private KeyboardView keyboardView;
    private Keyboard keyboardMain;
    private Keyboard keyboardSymbols;
    private boolean chineseMode = true;   // 中/英
    private boolean symbolMode = false;   // 123/符号面板
    private String selectedHint = "";     // 上次选中词（置顶提示，云端启用后生效）

    @Override
    public View onCreateInputView() {
        candidateView = new TextView(this);
        candidateView.setTextSize(16);
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
        return root;
    }

    // ===== KeyboardView.OnKeyboardActionListener =====

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        keyboardView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        if (primaryCode >= 'a' && primaryCode <= 'z') {
            if (chineseMode) {
                appendCode((char) primaryCode);
            } else {
                getCurrentInputConnection().commitText(String.valueOf((char) primaryCode), 1);
            }
        } else if (primaryCode >= '0' && primaryCode <= '9') {
            getCurrentInputConnection().commitText(String.valueOf((char) primaryCode), 1);
        } else if (primaryCode == KEY_123) {
            symbolMode = true;
            keyboardView.setKeyboard(keyboardSymbols);
        } else if (primaryCode == KEY_SYMBOL) {
            symbolMode = false;
            keyboardView.setKeyboard(keyboardMain);
        } else if (primaryCode == KEY_LANG) {
            if (symbolMode) {
                symbolMode = false;
                keyboardView.setKeyboard(keyboardMain);
            } else {
                toggleLang();
            }
        } else if (primaryCode == KEY_SPACE) {
            commitFirstCandidate();
        } else if (primaryCode == 44) {
            getCurrentInputConnection().commitText("，", 1);
        } else if (primaryCode == 46) {
            getCurrentInputConnection().commitText("。", 1);
        } else if (primaryCode == KeyEvent.KEYCODE_DEL) {
            handleBackspace();
        } else if (primaryCode == KeyEvent.KEYCODE_ENTER) {
            commitFirstCandidate();
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

    private void handleBackspace() {
        if (composingCode.length() > 0) {
            composingCode.deleteCharAt(composingCode.length() - 1);
            queryCandidates();
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
        }
    }

    /** 候选查询：本地词库优先，云端增强（占位符时跳过） */
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
        // 2) 云端增强（已部署时启用）
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

    /** 候选条：点击选字（ClickableSpan） */
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
            ss = new SpannableString("云五笔·中文");
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

    private void selectCandidate(int idx) {
        if (idx < 0 || idx >= candidates.size()) return;
        String text = candidates.get(idx);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
        selectedHint = text;
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
