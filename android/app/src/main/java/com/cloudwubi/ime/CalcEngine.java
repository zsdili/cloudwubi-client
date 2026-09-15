package com.cloudwubi.ime;

import java.util.ArrayList;
import java.util.List;

/**
 * 云五笔计算引擎（纯 Java，无 Android 依赖）
 *
 * 真实线上计算逻辑的唯一实现：CloudWubiIME 的计算路径（数字输入/运算符/= /退格）
 * 全部调用本类，禁止在别处另写副本 —— 保证 JVM 单元测试测的就是线上运行的代码。
 * 单元测试：android/tests/CalcEngineTest.java（javac 编译直接测本类）
 *
 * 逻辑来源（照抄线上）：CloudWubiIME.java
 *  - calcEval  (684-722)   calcAppendOp (2071-2108)
 *  - fmtResult (727-732)   commitCalc   (2108-2165)
 *  - hasCalcOp (1919-1925) handleBackspace (1156-1200)
 *  - 数字输入   (873-887)
 */
public class CalcEngine {

    /** 表达式缓冲（如 "2*3"） */
    public String calcBuffer = "";
    /** 最近一次直接上屏的数字（运算符按下时回收为表达式起点，如 "1.6"） */
    public String lastCalcInput = "";
    /** 上次计算结果（上屏后接运算符可继续计算） */
    public String lastCalcResult = "";
    /** 续算去重仅用于运算符自动续接（防误伤手动输入） */
    public boolean calcAuto = false;
    /** 是否带公式上屏（默认 true："1+2=3"） */
    public boolean calcFormula = true;

    /** 文本动作：由调用方（CloudWubiIME）对 InputConnection 执行 */
    public static class Action {
        /** 删除光标前 N 字符（0=不删） */
        public int delBefore;
        /** 上屏文本（null=不上屏） */
        public String commit;
        /** 是否为选区删除（commit="" 表示删除选区） */
        public boolean selectionDelete;

        public Action(int del, String cmt, boolean selDel) {
            delBefore = del;
            commit = cmt;
            selectionDelete = selDel;
        }
    }

    /** v0.5.35 反馈①：表达式是否已含运算符（决定数字是否进缓冲） */
    public static boolean hasCalcOp(String expr) {
        return expr.indexOf('+') >= 0 || expr.indexOf('-') >= 0 || expr.indexOf('*') >= 0
                || expr.indexOf('/') >= 0 || expr.indexOf('×') >= 0 || expr.indexOf('÷') >= 0;
    }

    /** 四则表达式求值（乘除优先，加减左结合；非法/除零返回 NaN） */
    public static double calcEval(String expr) {
        expr = expr.replace('×', '*').replace('÷', '/');
        List<Double> nums = new ArrayList<Double>();
        List<Character> ops = new ArrayList<Character>();
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
        double acc = nums.get(0);
        for (int i = 0; i < ops.size(); i++) {
            char op = ops.get(i);
            double b = nums.get(i + 1);
            acc = (op == '+') ? acc + b : acc - b;
        }
        return acc;
    }

    /** 结果格式化（整数返回 long；小数 1e8 精度） */
    public static String fmtResult(double v) {
        if (Double.isNaN(v)) return "错误";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 1e8) / 1e8);
    }

    /**
     * 数字键（对应线上 873-887）：
     * 表达式已含运算符 → 进缓冲；否则 → 直接上屏 + lastCalcInput 累积 + 清残留表达式
     */
    public Action onDigit(char d) {
        if (calcBuffer.length() > 0 && hasCalcOp(calcBuffer)) {
            calcBuffer += d;
            return new Action(0, null, false);
        }
        lastCalcInput += d;
        calcBuffer = "";
        return new Action(0, String.valueOf(d), false);
    }

    /**
     * 运算符键（对应线上 calcAppendOp 2071-2108）：
     * lastCalcInput 优先（删除已上屏数字再进缓冲）→ lastCalcResult 续算 → 手动追加
     * 返回 Action.delBefore = 需删除的已上屏数字长度（调用方执行 deleteSurroundingText）
     */
    public Action onOp(String op) {
        Action a = new Action(0, null, false);
        if (calcBuffer.isEmpty() && !lastCalcInput.isEmpty()) {
            a.delBefore = lastCalcInput.length();
            calcBuffer = lastCalcInput + op;
            lastCalcInput = "";
            calcAuto = true;
        } else if (calcBuffer.isEmpty() && !lastCalcResult.isEmpty()) {
            calcBuffer = lastCalcResult + op;
            calcAuto = true;
        } else {
            calcBuffer += op;
            calcAuto = false;
        }
        return a;
    }

    /**
     * "=" 或退出上屏（对应线上 commitCalc 2108-2165）：
     * textEndsWithResult 由调用方读文本框末尾判断（"上次结果是否已在屏"）
     * 已在屏则去重（1+2=3 上屏后 *4 → 上屏 "*4=12"，拼接 "1+2=3*4=12"）
     */
    public Action onEq(boolean textEndsWithResult) {
        if (calcBuffer.isEmpty()) return new Action(0, null, false);
        double v = calcEval(calcBuffer);
        Action a = new Action(0, null, false);
        if (Double.isNaN(v)) {
            a.commit = calcBuffer;
            lastCalcResult = "";
        } else {
            String res = fmtResult(v);
            String oldResult = lastCalcResult;
            lastCalcResult = res;
            if (calcBuffer.matches("^[0-9.]+$")) {
                a.commit = calcBuffer;
            } else if (calcFormula) {
                String expr = calcBuffer;
                boolean hasResult = textEndsWithResult && !oldResult.isEmpty();
                if (hasResult && expr.startsWith(oldResult)) {
                    expr = expr.substring(oldResult.length());
                    if (expr.isEmpty()) expr = res;
                }
                a.commit = expr + "=" + res;
            } else {
                a.commit = res;
            }
        }
        calcBuffer = "";
        calcAuto = false;
        if (!lastCalcResult.isEmpty()) lastCalcInput = lastCalcResult;
        return a;
    }

    /**
     * 退格（对应线上 handleBackspace 1156-1200）：
     * 数字面板且表达式非空 → 删表达式末字符；
     * 有选区（全选/部分选中）→ 删除选区 + 清 calc 状态（v0.5.57 根治"全选删除后重输带旧结果"）；
     * 否则 → 删 1 字符 + 清 calc 状态（v0.5.56 根治"删除后重输 22*3"）
     */
    public Action onBackspace(boolean panelMode1, boolean hasSelection) {
        if (panelMode1 && calcBuffer.length() > 0) {
            calcBuffer = calcBuffer.substring(0, calcBuffer.length() - 1);
            return new Action(0, null, false);
        }
        if (hasSelection) {
            lastCalcResult = "";
            lastCalcInput = "";
            return new Action(0, "", true);   // commitText("") 删除选区
        }
        lastCalcResult = "";
        lastCalcInput = "";
        return new Action(1, null, false);    // deleteSurroundingText(1,0)
    }
}
