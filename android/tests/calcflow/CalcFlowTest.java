package com.cloudwubi.ime;
/** v0.5.76 连续计算流程测试（真实 CalcEngine 代码，非模拟器）：
 *  场景1: 4-6=-2 上屏 → 再 +5 → -2+5=3（连续计算拼接，用户"4-6正常再+没反应"）
 *  场景2: 2*3=6 上屏 → 再 *4 → 6*4=24
 *  场景3: 上屏结果后按运算符必须进计算（lastCalcInput 续算） */
public class CalcFlowTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }
    static CalcEngine newEngine(boolean formula) {
        CalcEngine e = new CalcEngine();
        e.calcFormula = formula;
        return e;
    }
    public static void main(String[] args) {
        // 场景1a：仅结果模式（calcFormula=false）4-6=-2 → +5 → =3
        CalcEngine e1 = newEngine(false);
        e1.onDigit('4'); e1.onOp("-"); e1.onDigit('6');
        CalcEngine.Action a1 = e1.onEq(false);   // 4-6 = -2（仅结果）
        check("1a 首次 4-6 结果=-2", "-2".equals(a1.commit));
        e1.onOp("+");                            // lastCalcInput=-2 → 进计算
        e1.onDigit('5');
        CalcEngine.Action a2 = e1.onEq(false);   // -2+5 = 3
        check("1a 连续 -2+5 结果=3", "3".equals(a2.commit));
        // 场景1b：带式模式 4-6=-2 → +5 → 拼接 4-6=-2+5=3
        CalcEngine e2 = newEngine(true);
        e2.onDigit('4'); e2.onOp("-"); e2.onDigit('6');
        CalcEngine.Action b1 = e2.onEq(true);    // textEndsWithResult=true（4-6=-2 已在屏）
        check("1b 首次 4-6=-2 带式", "4-6=-2".equals(b1.commit));
        e2.onOp("+");
        e2.onDigit('5');
        CalcEngine.Action b2 = e2.onEq(true);    // 拼接
        check("1b 连续 4-6=-2+5=3", "4-6=-2+5=3".equals(b2.commit));
        // 场景2：2*3=6 → *4 → 6*4=24（带式）
        CalcEngine e3 = newEngine(true);
        e3.onDigit('2'); e3.onOp("*"); e3.onDigit('3');
        CalcEngine.Action c1 = e3.onEq(true);
        check("2 首次 2*3=6", "2*3=6".equals(c1.commit));
        e3.onOp("*");
        e3.onDigit('4');
        CalcEngine.Action c2 = e3.onEq(true);
        check("2 连续 2*3=6*4=24", "2*3=6*4=24".equals(c2.commit));
        // 场景3：上屏结果后按运算符进计算（lastCalcInput 续算，不丢表达式）
        CalcEngine e4 = newEngine(false);
        e4.onDigit('8'); e4.onOp("*"); e4.onDigit('4');
        e4.onEq(false);                          // 8*4=32
        CalcEngine.Action d1 = e4.onOp("+");     // lastCalcInput=32 → delBefore=2
        check("3 再按+ delBefore=2(删32)", d1.delBefore == 2);
        e4.onDigit('1'); e4.onDigit('6');
        CalcEngine.Action d2 = e4.onEq(false);   // 32+16=48
        check("3 32+16=48", "48".equals(d2.commit));
        System.out.println("== 连续计算测试: PASS " + pass + " FAIL " + fail);
        if (fail > 0) System.exit(1);
    }
}
