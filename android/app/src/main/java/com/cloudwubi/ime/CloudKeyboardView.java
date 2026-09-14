package com.cloudwubi.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.MotionEvent;

/**
 * CloudKeyboardView - 云五笔软键盘视图（v0.4.5 → v0.4.9）
 *
 * 功能：
 *  1. 字母键上滑输入数字/标点（Q→1 … P→0，A→@ … L→!，Z→- … M→;）
 *  2. 键帽左上角标注上滑符号（反馈⑦：键盘上能看到上滑可输入的数字/符号）
 *  3. 字母大小写显示支持（label 小写 + shiftLabel 大写）
 *  4. v0.4.9 反馈③：完全自绘 FLAT 纯色键帽（无渐变/立体），深浅色主题四件套实时切换
 *     ——同时规避高版本 Android SDK 移除 KeyboardView.setKeyBackground 等 API 的兼容问题
 */
public class CloudKeyboardView extends KeyboardView {

    private static final int SWIPE_THRESHOLD_DP = 30;
    private static final int LONG_PRESS_MS = 600;

    private int downY = 0;
    private boolean swipeTriggered = false;
    private Keyboard.Key downKey = null;
    private final int thresholdPx;
    /** v0.5.4 反馈③：长按支持（回车长按→换行） */
    private boolean longPressTriggered = false;
    private OnLongPressListener longPressListener;
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (downKey != null && longPressListener != null && !swipeTriggered) {
                longPressTriggered = true;
                longPressListener.onLongPress(downKey);
            }
        }
    };

    /** v0.5.4 反馈③：长按回调接口 */
    public interface OnLongPressListener {
        void onLongPress(Keyboard.Key key);
    }

    public void setOnLongPressListener(OnLongPressListener l) {
        longPressListener = l;
    }

    /** 上滑符号标注画笔 */
    private final Paint hintPaint;

    // ===== v0.4.9 FLAT 自绘主题 =====
    private int boardBg = 0xFFF3F4F6;       // 键盘底板
    private int keyBgNormal = 0xFFFFFFFF;   // 普通键面
    private int keyBgFunc = 0xFFE5E7EB;     // 功能键面
    private int keyTextColor = 0xFF1F2937;  // 键面文字
    private final float cornerPx;           // 键帽圆角
    private final float labelSizePx;
    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** v0.4.9：深浅色主题切换（键帽/底板/文字/上滑标注四件套） */
    public void applyTheme(boolean dark) {
        if (dark) {
            boardBg = 0xFF111827;
            keyBgNormal = 0xFF1F2937;
            keyBgFunc = 0xFF374151;
            keyTextColor = 0xFFF9FAFB;
            hintPaint.setColor(0xFF6B7280);
        } else {
            boardBg = 0xFFF3F4F6;
            keyBgNormal = 0xFFFFFFFF;
            keyBgFunc = 0xFFE5E7EB;
            keyTextColor = 0xFF1F2937;
            hintPaint.setColor(0xFF9CA3AF);
        }
        invalidate();
    }

    /** v0.4.9：上滑符号标注颜色（跟随主题） */
    public void setHintColor(int color) {
        hintPaint.setColor(color);
        invalidate();
    }

    public CloudKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        thresholdPx = Math.round(SWIPE_THRESHOLD_DP * context.getResources().getDisplayMetrics().density);
        hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setColor(0xFF9CA3AF);
        float density = context.getResources().getDisplayMetrics().density;
        hintPaint.setTextSize(10 * density);
        cornerPx = 6 * density;
        labelSizePx = 20 * density;
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** 上滑符号映射（v0.4.6 严格对齐参考截图）：
     *  行1 Q→1…P→0；行2 A→~ S→@ D→# F→$ G→% H→& J→* K→( L→)；
     *  行3 Z→' X→/ C→- V→_ B→: N→; M→` */
    public static int swipeSymbol(Keyboard.Key key) {
        if (key == null || key.codes == null || key.codes.length == 0) return 0;
        int code = key.codes[0];
        switch (code) {
            case 113: return '1';  // Q
            case 119: return '2';  // W
            case 101: return '3';  // E
            case 114: return '4';  // R
            case 116: return '5';  // T
            case 121: return '6';  // Y
            case 117: return '7';  // U
            case 105: return '8';  // I
            case 111: return '9';  // O
            case 112: return '0';  // P
            case 97: return '~';   // A
            case 115: return '@';  // S
            case 100: return '#';  // D
            case 102: return '$';  // F
            case 103: return '%';  // G
            case 104: return '&';  // H
            case 106: return '*';  // J
            case 107: return '(';  // K
            case 108: return ')';  // L
            case 122: return '\''; // Z
            case 120: return '/';  // X
            case 99: return '-';   // C
            case 118: return '_';  // V
            case 98: return ':';   // B
            case 110: return ';';  // N
            case 109: return '`';  // M
            case -106: return 0xFF01;  // v0.5.17 修复回归：！，键上滑 → ！（原设计走 IME.swipeUp 但 onTouchEvent 重写后死代码）
            case -108: return 0xFF1F;  // v0.5.17 修复回归：？。键上滑 → ？
            default: return 0;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int x = (int) ev.getX();
        int y = (int) ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = y;
                swipeTriggered = false;
                longPressTriggered = false;
                downKey = findKey(x, y);
                if (downKey != null) {
                    removeCallbacks(longPressRunnable);
                    postDelayed(longPressRunnable, LONG_PRESS_MS);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!swipeTriggered && downKey != null && (downY - y) > thresholdPx) {
                    swipeTriggered = true;
                    removeCallbacks(longPressRunnable);
                    int sym = swipeSymbol(downKey);
                    if (sym != 0) {
                        getOnKeyboardActionListener().onKey(sym, new int[]{sym});
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressRunnable);
                if (longPressTriggered) {
                    // v0.5.4 反馈③：长按已处理，吞掉本次点击避免触发普通键
                    longPressTriggered = false;
                    downKey = null;
                    return true;
                }
                downKey = null;
                break;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                downKey = null;
                break;
        }
        return super.onTouchEvent(ev);
    }

    /** v0.5.3 反馈⑨：数字/符号面板满宽居中——键盘按当前视图可用宽高等比缩放（幂等：以当前实际边界为基准） */
    private void fitKeyboardWidth() {
        Keyboard kb = getKeyboard();
        int w = getWidth();
        int h = getHeight();
        if (kb == null || w <= 0 || h <= 0) return;
        int availW = w - getPaddingLeft() - getPaddingRight();
        int availH = h - getPaddingTop() - getPaddingBottom();
        if (availW <= 0 || availH <= 0) return;
        try {
            int maxX = 0, maxY = 0;
            for (Keyboard.Key k : kb.getKeys()) {
                int right = k.x + k.width;
                int bottom = k.y + k.height;
                if (right > maxX) maxX = right;
                if (bottom > maxY) maxY = bottom;
            }
            if (maxX <= 0 || maxY <= 0) return;
            float sx = availW / (float) maxX;
            float sy = availH / (float) maxY;
            for (Keyboard.Key k : kb.getKeys()) {
                k.x = Math.round(k.x * sx);
                k.y = Math.round(k.y * sy);
                k.width = Math.round(k.width * sx);
                k.height = Math.round(k.height * sy);
            }
            invalidateAllKeys();
        } catch (Exception ignored) { }
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitKeyboardWidth();
    }

    @Override
    public void setKeyboard(Keyboard kb) {
        super.setKeyboard(kb);
        fitKeyboardWidth();
    }

    /** v0.4.9 FLAT 全自绘（键帽圆角纯色 + 文字 + 上滑标注），不依赖父类绘制 API */
    @Override
    public void onDraw(Canvas canvas) {
        Keyboard kb = getKeyboard();
        if (kb == null) return;
        canvas.drawColor(boardBg);
        int padLeft = getPaddingLeft();
        int padTop = getPaddingTop();
        for (Keyboard.Key key : kb.getKeys()) {
            float x = key.x + padLeft;
            float y = key.y + padTop;
            // 1) 键帽底色（功能键深一档）
            boolean pressed = (downKey == key);
            boolean func = isFuncKey(key);
            keyPaint.setColor(func ? (pressed ? 0xFFD1D5DB : keyBgFunc)
                                   : (pressed ? 0xFFE5E7EB : keyBgNormal));
            RectF r = new RectF(x + 2, y + 2, x + key.width - 2, y + key.height - 2);
            canvas.drawRoundRect(r, cornerPx, cornerPx, keyPaint);
            // 2) 键面文字（label 大小写由 IME updateKeyLabels 直接维护；v0.5.1 支持上下两行；v0.5.4 反馈①：全部水平居中，布局统一）
            float cx = x + key.width / 2f;
            if (key.label != null && key.label.length() > 0) {
                String lab = key.label.toString();
                int nl = lab.indexOf('\n');
                if (nl >= 0) {
                    String up = lab.substring(0, nl);
                    String down = lab.substring(nl + 1);
                    if (up.length() > 0) {
                        textPaint.setColor(keyTextColor);
                        textPaint.setTextSize(labelSizePx * 0.60f);
                        canvas.drawText(up, cx, y + key.height * 0.36f, textPaint);
                    }
                    if (down.length() > 0) {
                        textPaint.setColor(keyTextColor);
                        textPaint.setTextSize(labelSizePx);
                        canvas.drawText(down, cx, y + key.height * 0.72f, textPaint);
                    }
                } else {
                    textPaint.setColor(keyTextColor);
                    textPaint.setTextSize(labelSizePx);
                    float cy = y + key.height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f;
                    canvas.drawText(lab, cx, cy, textPaint);
                }
            }
            // v0.5.27 反馈⑤：去掉键帽上滑灰色标注（"！，"与"？。"各多一个灰色！？）——上滑功能保留，键面只显示主文字
        }
    }

    private boolean isFuncKey(Keyboard.Key key) {
        if (key == null || key.codes == null || key.codes.length == 0) return true;
        int c = key.codes[0];
        return c < 0 || c == 32 || c == 46 || c == 44;   // 功能键/空格/句号/逗号
    }

    private Keyboard.Key findKey(int x, int y) {
        Keyboard kb = getKeyboard();
        if (kb == null) return null;
        for (Keyboard.Key key : kb.getKeys()) {
            if (x >= key.x && x <= key.x + key.width
                    && y >= key.y && y <= key.y + key.height) {
                return key;
            }
        }
        return null;
    }
}
