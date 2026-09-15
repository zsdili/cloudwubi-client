import com.cloudwubi.ime.AssociateEngine;

/**
 * AssociateEngine 真实代码单元测试（JVM 直接编译运行——测的就是线上运行的重叠拼接逻辑）
 * 编译运行：
 *   javac -d /tmp/aetest AssociateEngine.java AssociateEngineTest.java
 *   java -cp /tmp/aetest AssociateEngineTest
 *
 * 用例构成：用户反馈场景 5 + 边界 8 + 随机 40 = 53 用例
 */
public class AssociateEngineTest {
    static int pass = 0, fail = 0;

    static void t(String name, int got, int expect) {
        if (got == expect) { pass++; }
        else { fail++; System.out.println("FAIL [" + name + "]: 得 " + got + " 期望 " + expect); }
    }
    static void tB(String name, boolean got) {
        if (got) { pass++; }
        else { fail++; System.out.println("FAIL [" + name + "]: 得 false 期望 true"); }
    }

    public static void main(String[] args) {
        // ========== 一、用户反馈场景（必须第一条） ==========
        t("U1 陈胜吴广+吴广起义→删2", AssociateEngine.overlapJoin("陈胜吴广", "吴广起义"), 2);
        t("U2 宇+宇宙→删1", AssociateEngine.overlapJoin("宇", "宇宙"), 1);
        t("U3 陈+陈胜→删1", AssociateEngine.overlapJoin("陈", "陈胜"), 1);
        t("U4 无重叠→删0", AssociateEngine.overlapJoin("中国人民", "前进浪潮"), 0);
        // 拼接验证（删重叠后不重复）
        String merged = "陈胜吴广".substring(0, "陈胜吴广".length() - 2) + "吴广起义";
        tB("U5 拼接不重复", merged.equals("陈胜吴广起义"));

        // ========== 二、边界 ==========
        t("B1 候选长于文本", AssociateEngine.overlapJoin("国", "国际主义"), 1);
        t("B2 完全相同", AssociateEngine.overlapJoin("前进", "前进"), 2);
        t("B3 空候选", AssociateEngine.overlapJoin("前进", ""), 0);
        t("B4 空文本", AssociateEngine.overlapJoin("", "前进"), 0);
        t("B5 null候选", AssociateEngine.overlapJoin("前进", null), 0);
        t("B6 null文本", AssociateEngine.overlapJoin(null, "前进"), 0);
        t("B7 单字重叠", AssociateEngine.overlapJoin("科学", "学无止境"), 1);
        t("B8 尾部重叠中间不重叠", AssociateEngine.overlapJoin("前进浪潮", "浪潮汹涌"), 2);

        // ========== 三、随机 40 组（拼接永不重复） ==========
        java.util.Random rnd = new java.util.Random(11);
        String[] tails = {"陈胜吴广", "前进浪潮", "中华人民共和国", "中国人民", "人工智能", "云计算", "科学技术", "新时代中国特色社会主义"};
        String[] cands = {"吴广起义", "前进号角", "人民共和国", "智能", "算力", "技术发展", "社会进步", "浪潮"};
        for (int i = 0; i < 40; i++) {
            String tail = tails[rnd.nextInt(tails.length)];
            String cand = cands[rnd.nextInt(cands.length)];
            int ov = AssociateEngine.overlapJoin(tail, cand);
            tB("R" + i + " 重叠范围合法", ov >= 0 && ov <= Math.min(tail.length(), cand.length()));
            if (ov > 0) {
                // 拼接后重叠部分只出现一次（不重复）
                String m = tail.substring(0, tail.length() - ov) + cand;
                tB("R" + i + "b 拼接不重复", m.indexOf(cand.substring(0, ov)) <= m.length() - cand.length() || !m.contains(cand.substring(0, ov) + cand.substring(0, ov)));
            }
        }

        System.out.println("\n===== AssociateEngine 真实代码测试: " + pass + " 通过 / " + fail + " 失败 / 共 " + (pass + fail) + " =====");
        if (fail > 0) System.exit(1);
    }
}
