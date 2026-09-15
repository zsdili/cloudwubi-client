import com.cloudwubi.ime.VersionUtil;

/** VersionUtil 真实代码单元测试（JVM 直接编译运行） */
public class VersionUtilTest {
    static int pass = 0, fail = 0;
    static void t(String name, int got, int expect) {
        if (got == expect) { pass++; }
        else { fail++; System.out.println("FAIL [" + name + "]: 得 " + got + " 期望 " + expect); }
    }
    public static void main(String[] args) {
        // 用户场景：v0.5.59 应等于当前最新
        t("U1 0.5.59==0.5.59", VersionUtil.compare("0.5.59", "0.5.59"), 0);
        t("U2 0.5.59<0.5.60", VersionUtil.compare("0.5.59", "0.5.60"), -1);
        t("U3 0.5.60>0.5.59", VersionUtil.compare("0.5.60", "0.5.59"), 1);
        t("U4 0.5.9<0.5.10(数字比较)", VersionUtil.compare("0.5.9", "0.5.10"), -1);
        t("U5 0.5.10>0.5.9", VersionUtil.compare("0.5.10", "0.5.9"), 1);
        t("U6 v前缀兼容", VersionUtil.compare("v0.5.60", "0.5.60"), 0);
        t("U7 段数不同", VersionUtil.compare("0.5", "0.5.0"), 0);
        t("U8 大版本", VersionUtil.compare("1.0.0", "0.9.9"), 1);
        t("U9 null安全", VersionUtil.compare(null, "0.5.60"), 0);
        t("U10 非数字安全", VersionUtil.compare("0.a.b", "0.5.60"), 0);
        // 随机 40 组与数值期望对照
        java.util.Random rnd = new java.util.Random(5);
        for (int i = 0; i < 40; i++) {
            int a1 = rnd.nextInt(10), a2 = rnd.nextInt(10), a3 = rnd.nextInt(10);
            int b1 = rnd.nextInt(10), b2 = rnd.nextInt(10), b3 = rnd.nextInt(10);
            int got = VersionUtil.compare(a1 + "." + a2 + "." + a3, b1 + "." + b2 + "." + b3);
            int exp = a1 * 100 + a2 * 10 + a3 > b1 * 100 + b2 * 10 + b3 ? 1 :
                      a1 * 100 + a2 * 10 + a3 < b1 * 100 + b2 * 10 + b3 ? -1 : 0;
            t("R" + i + " " + a1 + "." + a2 + "." + a3 + " vs " + b1 + "." + b2 + "." + b3, got, exp);
        }
        System.out.println("\n===== VersionUtil 真实代码测试: " + pass + " 通过 / " + fail + " 失败 / 共 " + (pass + fail) + " =====");
        if (fail > 0) System.exit(1);
    }
}
