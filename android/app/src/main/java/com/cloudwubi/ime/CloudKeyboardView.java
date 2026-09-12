package com.cloudwubi.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.MotionEvent;

/**
 * CloudKeyboardView - 云五笔软键盘视图（v0.4.5）
 *
 * 功能：
 *  1. 字母键上滑输入数字/标点（Q→1 … P→0，A→@ … L→!，Z→- … M→;）
 *  2. 键帽左上角标注上滑符号（反馈⑦：键盘上能看到上滑可输入的数字/符号）
 *  3. 字母大小写显示支持（label 小写 + shiftLabel 大写，由 IME 控制 setShifted）
 */
public class CloudKeyboardView extends KeyboardView {

    private static final int SWIPE_THRESHOLD_DP = 30;

    private int downY = 0;
    private boolean swipeTriggered = false;
    private Keyboard.Key downKey = null;
    private final int thresholdPx;

    // 上滑符号标注画笔
    private final Paint hintPaint;

    public CloudKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        thresholdPx = Math.round(SWIPE_THRESHOLD_DP * context.getResources().getDisplayMetrics().density);
        hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setColor(0xFF9CA3AF);
        float density = context.getResources().getDisplayMetrics().density;
        hintPaint.setTextSize(10 * density);
    }

    /** 上滑符号映射：字母键 -> 数字/标点（0 表示无映射） */
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
            case 97: return '@';   // A
            case 115: return '#';  // S
            case 100: return '$';  // D
            case 102: return '%';  // F
            case 103: return '&';  // G
            case 104: return '*';  // H
            case 106: return '(';  // J
            case 107: return ')';  // K
            case 108: return '!';  // L
            case 122: return '-';  // Z
            case 120: return '_';  // X
            case 99: return '=';   // C
            case 118: return '+';  // V
            case 98: return '[';   // B
            case 110: return ']';  // N
            case 109: return ';';  // M
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
                downKey = findKey(x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!swipeTriggered && downKey != null && (downY - y) > thresholdPx) {
                    swipeTriggered = true;
                    int sym = swipeSymbol(downKey);
                    if (sym != 0) {
                        getOnKeyboardActionListener().onKey(sym, new int[]{sym});
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                downKey = null;
                break;
        }
        return super.onTouchEvent(ev);
    }

    /** 绘制：键盘主体 + 键帽左上角上滑符号标注（反馈⑦） */
    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Keyboard kb = getKeyboard();
        if (kb == null) return;
        int padLeft = getPaddingLeft();
        int padTop = getPaddingTop();
        for (Keyboard.Key key : kb.getKeys()) {
            int sym = swipeSymbol(key);
            if (sym == 0) continue;
            String s = String.valueOf((char) sym);
            float cx = key.x + padLeft + key.width * 0.28f;
            float cy = key.y + padTop + key.height * 0.28f;
            canvas.drawText(s, cx, cy, hintPaint);
        }
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
