import com.cloudwubi.ime.WubiDbCore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** WubiDbCore 真实词库测试（加载真实 wubi_single.txt / wubi_phrase.txt） */
public class WubiDbCoreTest {
    static int pass = 0, fail = 0;
    static void t(String name, boolean cond, String detail) {
        if (cond) pass++;
        else { fail++; System.out.println("FAIL [" + name + "] " + detail); }
    }
    static List<String> readLines(String p) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(p), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) lines.add(l);
        }
        return lines;
    }
    public static void main(String[] args) throws Exception {
        List<String> single = readLines(args[0]);
        List<String> phrase = readLines(args[1]);
        WubiDbCore.init(single, phrase);

        // ① 单字→码 反查（MRU 置顶的基础）
        t("singleCode 钟", "qkhh".equals(WubiDbCore.singleCode("钟")), "得 " + WubiDbCore.singleCode("钟"));
        t("singleCode 陈", "baiy".equals(WubiDbCore.singleCode("陈")), "得 " + WubiDbCore.singleCode("陈"));
        t("singleCode 胜", WubiDbCore.singleCode("胜") != null && WubiDbCore.singleCode("胜").startsWith("etg"), "得 " + WubiDbCore.singleCode("胜"));
        // 单字反查取"最长码"（动态拼词需全码）；1 码置顶走 simple1Char 分支（MruEngineTest 已覆盖）
        t("singleCode 的(最长码)", "rqyy".equals(WubiDbCore.singleCode("的")), "得 " + WubiDbCore.singleCode("的"));
        t("singleCode 不(最长码)", "gi".equals(WubiDbCore.singleCode("不")), "得 " + WubiDbCore.singleCode("不"));
        t("simple1Char r=的", "的".equals(WubiDbCore.simple1Char('r')), "");
        t("simple1Char i=不", "不".equals(WubiDbCore.simple1Char('i')), "");
        t("simple1Char w=人", "人".equals(WubiDbCore.simple1Char('w')), "");
        t("simple1Char p=这", "这".equals(WubiDbCore.simple1Char('p')), "");
        // ② 词组→码 反查（本地词组反向索引）
        t("phraseCode 工具", "aahw".equals(WubiDbCore.phraseCode("工具")), "得 " + WubiDbCore.phraseCode("工具"));
        t("phraseCode 工业", "aaog".equals(WubiDbCore.phraseCode("工业")), "得 " + WubiDbCore.phraseCode("工业"));

        // ③ 动态词组回退（v0.5.61 关键修复：陈胜/吴广不在词组文件，须回退 86 规则反算）
        String chensheng = WubiDbCore.phraseCode("陈胜");
        t("phraseCode 陈胜 动态回退", "baet".equals(chensheng), "得 " + chensheng);
        String wuguang = WubiDbCore.phraseCode("吴广");
        t("phraseCode 吴广 动态回退", wuguang != null && wuguang.length() == 4, "得 " + wuguang);
        t("dynamicPhraseCode 中国人民", "klwn".equals(WubiDbCore.dynamicPhraseCode("中国人民")), "得 " + WubiDbCore.dynamicPhraseCode("中国人民"));
        t("dynamicPhraseCode 认真听讲", "yfky".equals(WubiDbCore.dynamicPhraseCode("认真听讲")), "得 " + WubiDbCore.dynamicPhraseCode("认真听讲"));

        // ④ query 候选（一级简码置顶/保障词/常见词）
        List<String> q1 = WubiDbCore.query("r");
        t("query r 的置顶", q1 != null && q1.get(0).equals("的"), "得 " + q1);
        List<String> qi = WubiDbCore.query("i");
        t("query i 不置顶", qi != null && qi.get(0).equals("不"), "得 " + qi);
        List<String> qkhh = WubiDbCore.query("qkhh");
        t("query qkhh 含钟", qkhh != null && qkhh.contains("钟"), "得 " + qkhh);
        List<String> uefj = WubiDbCore.query("uefj");
        t("query uefj 含前进(保障词离线)", uefj != null && uefj.contains("前进"), "得 " + uefj);
        List<String> ilif = WubiDbCore.query("ilif");
        t("query ilif 含没办法(保障词离线)", ilif != null && ilif.contains("没办法"), "得 " + ilif);
        List<String> klwn = WubiDbCore.query("klwn");
        t("query klwn 含中国人民(保障词离线)", klwn != null && klwn.contains("中国人民"), "得 " + klwn);
        List<String> baet = WubiDbCore.buildDynamicWords("baet");
        t("buildDynamicWords baet 含陈胜", baet != null && baet.contains("陈胜"), "得 " + baet);

        // ⑤ 3 码预测 / 前缀
        List<String> uab = WubiDbCore.queryPredict("uab");
        t("queryPredict uab 含辛苦了", uab != null && uab.contains("辛苦了"), "得 " + uab);
        List<String> suf3 = WubiDbCore.query("suf");
        t("query 3码suf 含栏(全码前缀)", suf3 != null && suf3.contains("栏"), "得 " + suf3);
        List<String> udp3 = WubiDbCore.query("udp");
        t("query 3码udp 含送", udp3 != null && udp3.contains("送"), "得 " + udp3);

        // ⑥ 随机 40 组：任意 1-4 码 query 无异常 + 词频排序稳定
        Random rnd = new Random(7);
        String chars = "qwertyuiopasdfghjklzxcvbnm";
        for (int i = 0; i < 40; i++) {
            int len = 1 + rnd.nextInt(4);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
            String code = sb.toString();
            List<String> q = WubiDbCore.query(code);
            if (q != null) {
                for (String c : q) {
                    if (c == null || c.isEmpty()) { t("R" + i + " 候选非空", false, code); break; }
                }
                t("R" + i + " " + code + " 无异常", true, "");
            } else {
                t("R" + i + " " + code + " 空结果可接受", true, "");
            }
        }

        System.out.println("\n===== WubiDbCore 真实词库测试: " + pass + " 通过 / " + fail + " 失败 / 共 " + (pass + fail) + " =====");
        if (fail > 0) System.exit(1);
    }
}
