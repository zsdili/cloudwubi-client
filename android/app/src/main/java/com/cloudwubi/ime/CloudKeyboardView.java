package com.cloudwubi.ime;

import android.content.Context;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.MotionEvent;

/**
 * CloudKeyboardView - 支持按键上滑输入数字/标点（v0.4.4）
 *
 * 用户在字母键上向上滑动时，触发该键对应的符号输出：
 *   Q→1 W→2 E→3 R→4 T→5 Y→6 U→7 I→8 O→9 P→0
 *   A→@ S→# D→$ F→% G→& H→* J→( K→) L→!
 *   Z→- X→_ C→= V→+ B→[ N→] M→;
 */
public class CloudKeyboardView extends KeyboardView {

    private static final int SWIPE_THRESHOLD_DP = 30;

    private int downY = 0;
    private boolean swipeTriggered = false;
    private Keyboard.Key downKey = null;
    private final int thresholdPx;

    public CloudKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        thresholdPx = Math.round(SWIPE_THRESHOLD_DP * context.getResources().getDisplayMetrics().density);
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

    /** 字母键 -> 上滑符号（数字/标点） */
    private int swipeSymbol(Keyboard.Key key) {
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
}
