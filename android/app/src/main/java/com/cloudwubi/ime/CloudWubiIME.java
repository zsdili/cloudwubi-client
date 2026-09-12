package com.cloudwubi.ime;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
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
 * CloudWubiIME - 云五笔 Android 输入法服务
 *
 * 设计原则（先科学后先进）：
 *   1. 端侧极简：只做按键采集、编码校验、结果渲染（<800KB）
 *   2. 核心算力在云端：动态构词 + AI 排序
 *   3. 断网降级：本地一级简码兜底（25 个高频字）
 *   4. 候选规则（用户固化）：
 *      1码 → 高频单字；2码 → 先单字再二字词；上次选中置顶；3码 → 预测第4码词组
 */
public class CloudWubiIME extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    /** 云端网关地址（部署后替换，默认本地演示） */
    private static final String GATEWAY_URL =
            "https://YOUR-GATEWAY-URL/release/wubi/query";  // TODO: 部署后替换

    /** 本地一级简码兜底表（断网可用） */
    private static final String[] FALLBACK_CODES = {
        "g","f","d","s","a","h","j","k","l","m",
        "t","r","e","w","q","y","u","i","o","p",
        "n","b","v","c","x"
    };
    private static final String[] FALLBACK_CHARS = {
        "一","地","在","要","工","上","是","中","国","同",
        "和","的","有","人","我","主","产","不","为","这",
        "民","了","发","以","经"
    };

    private final StringBuilder composingCode = new StringBuilder();
    private List<String> candidates = new ArrayList<>();
    private TextView candidateView;
    private KeyboardView keyboardView;
    private Keyboard keyboard;

    @Override
    public View onCreateInputView() {
        candidateView = new TextView(this);
        candidateView.setTextSize(18);
        candidateView.setPadding(12, 10, 12, 10);
        candidateView.setTextColor(0xFF333333);
        candidateView.setBackgroundColor(0xFFFFFFFF);

        // 软键盘（数字选字行 + 五笔26键）
        keyboard = new Keyboard(this, R.xml.keyboard_qwerty);
        keyboardView = new KeyboardView(this, null);
        keyboardView.setKeyboard(keyboard);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F5);
        root.addView(candidateView);
        root.addView(keyboardView);
        return root;
    }

    // ===== KeyboardView.OnKeyboardActionListener =====

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        if (primaryCode >= 'a' && primaryCode <= 'z') {
            // 五笔编码键（含 z 万能键）
            appendCode((char) primaryCode);
        } else if (primaryCode >= '0' && primaryCode <= '9') {
            // 数字选字：1-9 对应候选1-9，0 对应候选10
            int idx = (primaryCode == '0') ? 9 : (primaryCode - '1');
            selectCandidate(idx);
        } else if (primaryCode == KeyEvent.KEYCODE_DEL) {
            clearComposing();
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
        if (c >= 'a' && c <= 'y') {
            appendCode((char) c);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                clearComposing();
                return true;
            case KeyEvent.KEYCODE_ENTER:
                commitFirstCandidate();
                return true;
            case KeyEvent.KEYCODE_SPACE:
                commitFirstCandidate();
                return true;
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                int idx = keyCode - KeyEvent.KEYCODE_0;
                if (idx == 0) idx = 9;
                selectCandidate(idx);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void appendCode(char c) {
        if (composingCode.length() >= 4) return;
        composingCode.append(c);
        queryCandidates();
    }

    private void clearComposing() {
        composingCode.setLength(0);
        candidates.clear();
        updateCandidateView();
    }

    /** 候选查询：云端优先，断网本地兜底 */
    private void queryCandidates() {
        candidates.clear();
        String code = composingCode.toString();

        // 1) 本地一级简码兜底（1码时）
        if (code.length() == 1) {
            for (int i = 0; i < FALLBACK_CODES.length; i++) {
                if (FALLBACK_CODES[i].equals(code)) {
                    candidates.add(FALLBACK_CHARS[i]);
                    break;
                }
            }
        }

        // 2) 云端查询（构词 + AI 排序）
        List<String> cloud = queryGateway(code);
        if (cloud != null) {
            candidates.addAll(cloud);
        }

        updateCandidateView();
    }

    /** 云端网关查询（线程安全：异步执行） */
    private List<String> queryGateway(final String code) {
        if (code.isEmpty()) return null;
        final java.util.concurrent.atomic.AtomicReference<List<String>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
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
            } catch (Exception ignored) {
                // 断网降级：返回 null，用本地兜底
            }
        });
        t.start();
        try { t.join(3000); } catch (InterruptedException ignored) {}
        return result.get();
    }

    /** 解析网关返回：candidates（单字码点）+ phrases（词组） */
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
        } catch (Exception ignored) {}
        return list;
    }

    private void updateCandidateView() {
        if (candidateView == null) return;
        if (candidates.isEmpty()) {
            candidateView.setText(composingCode.length() > 0 ? "…" : "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(i + 1).append(".").append(candidates.get(i)).append("  ");
        }
        candidateView.setText(sb.toString());
    }

    private void commitFirstCandidate() {
        if (!candidates.isEmpty()) selectCandidate(0);
    }

    private void selectCandidate(int idx) {
        if (idx < 0 || idx >= candidates.size()) return;
        String text = candidates.get(idx);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
        reportSelection(text);
        clearComposing();
    }

    /** 上报选词（云端 MRU 学习，尽力而为） */
    private void reportSelection(final String phrase) {
        Thread t = new Thread(() -> {
            try {
                URL url = new URL(GATEWAY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                String body = "{\"learn\":\"" + phrase + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        });
        t.start();
    }
}
