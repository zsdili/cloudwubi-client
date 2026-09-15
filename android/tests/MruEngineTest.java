import com.cloudwubi.ime.MruEngine;
import java.util.*;

/** MruEngine 真实代码测试（MRU 置顶：候选可见即置顶——动态/云端词全命中） */
public class MruEngineTest {
    static int pass = 0, fail = 0;
    static void t(String name, boolean cond, String detail) {
        if (cond) pass++;
        else { fail++; System.out.println("FAIL [" + name + "] " + detail); }
    }
    static List<String> L(String... a) { return new ArrayList<>(Arrays.asList(a)); }
    public static void main(String[] args) {
        // ① 用户场景：打"钟"上屏后，再打 qkhh → "钟"置顶
        List<String> c1 = L("钟", "锅", "锺");
        MruEngine.applyTop(c1, "钟", new ArrayList<>());
        t("U1 钟置顶", c1.get(0).equals("钟"), c1.toString());

        // ② 动态词：上屏"陈胜"（不在词组文件）→ 再打 baet → 置顶
        List<String> c2 = L("陈胜", "陈", "胜");
        MruEngine.applyTop(c2, "陈胜", new ArrayList<>());
        t("U2 动态词陈胜置顶", c2.get(0).equals("陈胜"), c2.toString());

        // ③ 云端词：候选来自云端（如"吴广起义"）→ 置顶
        List<String> c3 = L("吴广起义", "共产主义", "马列主义");
        MruEngine.applyTop(c3, "吴广起义", new ArrayList<>());
        t("U3 云端词置顶", c3.get(0).equals("吴广起义"), c3.toString());

        // ④ lastSelected 不在候选 → 不插入新词（保持原顺序）
        List<String> c4 = L("白", "百", "柏");
        List<String> orig4 = new ArrayList<>(c4);
        MruEngine.applyTop(c4, "钟", new ArrayList<>());
        t("U4 不可见不插入", c4.equals(orig4), c4.toString());

        // ⑤ mruList 多词顺序（新上屏在前）
        List<String> c5 = L("吴广", "陈胜", "吴", "广");
        List<String> mru5 = L("吴广", "陈胜");
        MruEngine.applyTop(c5, "吴广", mru5);
        t("U5 mru顺序", c5.get(0).equals("吴广") && c5.get(1).equals("陈胜"), c5.toString());

        // ⑥ 新上屏覆盖旧（lastSelected 与 mruList 首项一致）
        List<String> c6 = L("钟", "胜", "陈");
        List<String> mru6 = L("钟", "胜");
        MruEngine.applyTop(c6, "钟", mru6);
        t("U6 最近置顶", c6.get(0).equals("钟"), c6.toString());

        // ⑦ 空列表安全
        List<String> c7 = new ArrayList<>();
        MruEngine.applyTop(c7, "钟", new ArrayList<>());
        t("U7 空列表安全", c7.isEmpty(), "");

        // ⑧ null 安全
        MruEngine.applyTop(null, "钟", new ArrayList<>());
        t("U8 null安全", true, "");

        // ⑨ 候选含 lastSelected 但不含 mruList 其他 → 只置顶可见项
        List<String> c9 = L("陈", "陈胜");
        MruEngine.applyTop(c9, "陈胜", L("吴广", "陈胜"));
        t("U9 只置顶可见", c9.get(0).equals("陈胜") && c9.get(1).equals("陈"), c9.toString());

        // ⑩ 已上屏字"钟" + 候选含"钟"与"钟表" → 单字置顶
        List<String> c10 = L("钟表", "钟", "锅");
        MruEngine.applyTop(c10, "钟", new ArrayList<>());
        t("U10 单字置顶", c10.get(0).equals("钟"), c10.toString());

        // 随机 40 组：置顶后首项必为 lastSelected（若可见）
        Random rnd = new Random(11);
        String[] pool = {"钟", "胜", "陈", "吴", "广", "前进", "中国人民", "的", "不", "人"};
        for (int i = 0; i < 40; i++) {
            List<String> cl = new ArrayList<>();
            for (int j = 0; j < 6; j++) cl.add(pool[rnd.nextInt(pool.length)]);
            List<String> uniq = new ArrayList<>(new LinkedHashSet<>(cl));
            String sel = uniq.get(rnd.nextInt(uniq.size()));
            int before = uniq.size();
            MruEngine.applyTop(uniq, sel, new ArrayList<>());
            t("R" + i + " 置顶=" + sel, uniq.get(0).equals(sel) && uniq.size() == before, uniq.toString());
        }

        System.out.println("\n===== MruEngine 真实代码测试: " + pass + " 通过 / " + fail + " 失败 / 共 " + (pass + fail) + " =====");
        if (fail > 0) System.exit(1);
    }
}
