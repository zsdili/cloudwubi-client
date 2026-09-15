import com.cloudwubi.ime.CalcEngine;

/**
 * CalcEngine 真实代码单元测试（JVM 直接编译运行——测的就是线上运行的计算引擎）
 * 编译运行：
 *   javac -d /tmp/cetest CalcEngine.java CalcEngineTest.java
 *   java -cp /tmp/cetest CalcEngineTest
 *
 * 用例构成：用户反馈场景(必须第一条) 5 + 基本四则 7 + 小数点 1 + 状态机单测 8
 *          + 边界 2 + 随机删除重算 40 = 63 用例
 */
public class CalcEngineTest {
    static int pass = 0, fail = 0;

    /** 真实文本框模拟（只关心末尾，与 CalcEngine 交互同真实 InputConnection 路径） */
    static class Sim {
        CalcEngine ce = new CalcEngine();
        StringBuilder text = new StringBuilder();
        void digit(char d) {
            CalcEngine.Action a = ce.onDigit(d);
            if (a.commit != null) text.append(a.commit);
        }
        void op(String o) {
            CalcEngine.Action a = ce.onOp(o);
            if (a.delBefore > 0) text.delete(Math.max(0, text.length() - a.delBefore), text.length());
        }
        void eq() {
            boolean ends = text.toString().endsWith(ce.lastCalcResult);
            CalcEngine.Action a = ce.onEq(ends);
            if (a.commit != null) text.append(a.commit);
        }
        void bs(boolean panelMode1, boolean sel) {
            CalcEngine.Action a = ce.onBackspace(panelMode1, sel);
            if (a.selectionDelete) text.setLength(0);
            else if (a.delBefore > 0) text.delete(Math.max(0, text.length() - a.delBefore), text.length());
        }
        void input(String expr, boolean end) { // 完整输入表达式后按 =
            for (int i = 0; i < expr.length(); i++) {
                char c = expr.charAt(i);
                if (Character.isDigit(c) || c == '.') digit(c);
                else op(String.valueOf(c));
            }
            eq();
        }
    }

    static void t(String name, String got, String expect) {
        if (got != null && got.equals(expect)) { pass++; }
        else { fail++; System.out.println("FAIL [" + name + "]: 得 '" + got + "' 期望 '" + expect + "'"); }
    }
    static void tB(String name, boolean got) {
        if (got) { pass++; }
        else { fail++; System.out.println("FAIL [" + name + "]: 得 false 期望 true"); }
    }

    public static void main(String[] args) {
        // ========== 一、用户反馈场景（必须第一条，不得删除） ==========
        // 场景1（v0.5.57）：2*3=6 全选删除后重输 → 不得带旧 6
        Sim s = new Sim();
        s.input("2*3", false);
        t("U1 2*3=6", s.text.toString(), "2*3=6");
        s.bs(false, true);           // 全选删除（清状态）
        t("U1b 全选删除后清空", s.text.toString(), "");
        s.input("2*3", false);
        t("U1c 全选删除后重输", s.text.toString(), "2*3=6");

        // 场景2（v0.5.56）：2*3=6 退格删除后重输
        s = new Sim();
        s.input("2*3", false);
        for (int i = 0; i < 6; i++) s.bs(false, false);
        t("U2 退格全删后清空", s.text.toString(), "");
        s.input("2*3", false);
        t("U2b 退格全删后重输", s.text.toString(), "2*3=6");

        // 场景3（v0.5.32 顽疾）：1+2=3 上屏后 *4 → 1+2=3*4=12（不得 33）
        s = new Sim();
        s.input("1+2", false);
        s.op("*"); s.digit('4'); s.eq();
        t("U3 续算1+2=3*4=12", s.text.toString(), "1+2=3*4=12");

        // 场景4（v0.5.32 顽疾）：8*4=32 上屏后 /16 → 8*4=32/16=2（不得 3232）
        s = new Sim();
        s.input("8*4", false);
        s.op("/"); s.digit('1'); s.digit('6'); s.eq();
        t("U4 续算8*4=32/16=2", s.text.toString(), "8*4=32/16=2");

        // 场景5（v0.5.54）：2*3 不得错成 6*3（上屏 2 后按 * 应回收"2"而非上次结果"6"）
        s = new Sim();
        s.digit('2'); s.op("*"); s.digit('3'); s.eq();
        t("U5 2*3=6 不混入旧结果", s.text.toString(), "2*3=6");

        // ========== 二、基本四则 ==========
        String[][] cases = {
            {"1+2", "1+2=3"}, {"8/4", "8/4=2"}, {"7-3", "7-3=4"},
            {"9*9", "9*9=81"}, {"100-1", "100-1=99"}, {"2+3*4", "2+3*4=14"},
            {"1+2*3-4/2", "1+2*3-4/2=5"}
        };
        for (String[] c : cases) {
            s = new Sim(); s.input(c[0], false);
            t("四则" + c[0], s.text.toString(), c[1]);
        }
        // 小数点（1.6*3=4.8，浮点精度）
        s = new Sim();
        s.digit('1'); s.digit('.'); s.digit('6'); s.op("*"); s.digit('3'); s.eq();
        t("小数1.6*3=4.8", s.text.toString(), "1.6*3=4.8");

        // ========== 三、状态机单测 ==========
        // onDigit 纯数字直接上屏
        s = new Sim(); s.digit('5');
        t("S1 纯数字上屏", s.text.toString(), "5");
        // 连续数字累积 lastCalcInput（"1.6" 整体回收）
        s = new Sim(); s.digit('1'); s.digit('.'); s.digit('6');
        tB("S2 lastCalcInput=1.6", s.ce.lastCalcInput.equals("1.6"));
        // onOp 删除已上屏数字（delBefore）
        s = new Sim(); s.digit('2');
        CalcEngine.Action a = s.ce.onOp("*");
        tB("S3 delBefore=1", a.delBefore == 1);
        tB("S3b calcBuffer=2*", s.ce.calcBuffer.equals("2*"));
        // 运算符后数字进缓冲
        s = new Sim(); s.digit('2'); s.op("*"); s.digit('3');
        tB("S4 表达式数字进缓冲", s.ce.calcBuffer.equals("2*3"));
        // 上屏结果后 onOp 续算
        s = new Sim(); s.input("2+3", false);
        s.op("+");
        tB("S5 结果续算 5+", s.ce.calcBuffer.equals("5+"));
        // 连续计算链 2+3=5 +7=12 *2=24
        s = new Sim();
        s.input("2+3", false); s.op("+"); s.digit('7'); s.eq();
        t("S6 链2+3=5+7=12", s.text.toString(), "2+3=5+7=12");
        s.op("*"); s.digit('2'); s.eq();
        t("S6b 链续*2=24", s.text.toString(), "2+3=5+7=12*2=24");
        // 数字面板退格删表达式
        s = new Sim(); s.digit('2'); s.op("*"); s.digit('3');
        a = s.ce.onBackspace(true, false);
        tB("S7 面板退格删表达式", s.ce.calcBuffer.equals("2*"));
        // 除零：onEq 返回 commit=calcBuffer（不崩溃）
        s = new Sim(); s.digit('1'); s.op("/"); s.digit('0');
        boolean ends = s.text.toString().endsWith(s.ce.lastCalcResult);
        a = s.ce.onEq(ends);
        tB("S8 除零不崩", a.commit != null && !a.commit.isEmpty());

        // ========== 四、随机 40 组（删除重算稳定性） ==========
        java.util.Random rnd = new java.util.Random(7);
        String[] ops = {"+", "-", "*"};
        for (int i = 0; i < 40; i++) {
            s = new Sim();
            int a1 = 1 + rnd.nextInt(9), b1 = 1 + rnd.nextInt(9);
            String op1 = ops[rnd.nextInt(3)];
            s.input(a1 + op1 + b1, false);
            // 随机删除（选区 or 退格全删）
            if (rnd.nextBoolean()) s.bs(false, true);
            else for (int k = 0; k < 8; k++) s.bs(false, false);
            int x = 1 + rnd.nextInt(9), y = 1 + rnd.nextInt(9);
            String op2 = ops[rnd.nextInt(3)];
            s.input(x + op2 + y, false);
            double expectV = op2.equals("+") ? x + y : op2.equals("-") ? x - y : x * y;
            String expect = x + op2 + y + "=" + (long) expectV;
            t("R" + i + " 随机删除重算", s.text.toString(), expect);
        }

        System.out.println("\n===== CalcEngine 真实代码测试: " + pass + " 通过 / " + fail + " 失败 / 共 " + (pass + fail) + " =====");
        if (fail > 0) System.exit(1);
    }
}
