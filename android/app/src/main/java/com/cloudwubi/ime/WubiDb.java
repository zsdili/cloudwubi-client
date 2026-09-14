package com.cloudwubi.ime;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WubiDb - 云五笔本地五笔86精简词库（离线可用，v0.4.4）
 *
 * 数据来源：cloudwubi-rules（Rime 官方五笔86码表 LGPL-3.0 派生）
 *   单字 6563 条（GB2312 一级）+ 词组 4146 条（4000 常用二字节词 + 46 热词）
 * 词库存于 res/raw 资源，运行时加载（规避 Java 64KB 方法字节码限制）
 * 设计：端侧兜底保证"可打字"，云端负责无限扩展（先科学后先进）
 */
public final class WubiDb {

    private static Map<String, List<String>> singleIndex;
    private static Map<String, List<String>> phraseIndex;
    /** v0.5.0 反馈②：词组→编码 反向索引（MRU 置顶排序用） */
    private static Map<String, String> phraseCodeIndex;
    /** v0.5.4 反馈②：单字→编码 反向索引（lastSelected 置顶需编码匹配，避免错位霸榜） */
    private static Map<String, String> singleCodeIndex;
    /** v0.5.13 反馈②：高频搭配+积极成语联想表键（associate.txt 纯文本，前缀匹配 + 含字启发双用） */
    private static final String ASSOC_KEY = "zzzz";
    // ==================== v0.5.23 动态拼词引擎（钟总核心思路） ====================
    // 基础库（字根/一级/二级/三级简码单字全码）+ 86 版词组编码规则 → 动态生成词组
    // 不用大词库：体积小、速度快、词组无限（排列组合即创新）
    // 86 规则：2字词=前字前2码+后字前2码；3字词=前2字各1码+末字前2码；4字词=前3字各1码+末字1码
    /** 前缀索引：编码前缀(1-3码) -> 字列表（简码精确 + 全码前缀匹配，遍历序保证精确在前） */
    private static Map<String, List<String>> prefixIndex;
    /** 单字全码索引：字 -> 全码（拼词取码用；构建时 4 码/最长优先） */
    private static Map<String, String> fullCodeIndex;
    /** 保障词（特例置顶）：动态规则易错或用户必查的常用词，按编码精确置顶 */
    private static final String[] GUARANTEED = {
        "dgqe三角", "dgqe感触", "ilif没办法", "imlf没办法", "uabn辛苦了", "ytyt谢谢",
        "trwu我们", "vbrq好的", "ddgj大理", "uefj前进", "wqvb你好", "aawt工作"
    };

    private WubiDb() { }

    /** 由 IME onCreate 调用一次，加载词库（v0.5.5：词库移至 assets，APK 内文本可压缩省体积） */
    public static synchronized void init(Context ctx) {
        if (singleIndex != null) return;
        singleIndex = new HashMap<>();
        phraseIndex = new HashMap<>();
        phraseCodeIndex = new HashMap<>();
        singleCodeIndex = new HashMap<>();
        loadAsset(ctx, "wubi_single.txt", singleIndex);
        loadAsset(ctx, "wubi_phrase.txt", phraseIndex);
        // v0.5.20（用户指令）：联想词表不再加载（联想功能暂时去除，省体积）
        // 词组反向索引（同一词可能多码，保留首条）
        if (phraseIndex != null) {
            for (Map.Entry<String, List<String>> e : phraseIndex.entrySet()) {
                for (String w : e.getValue()) {
                    if (!phraseCodeIndex.containsKey(w)) phraseCodeIndex.put(w, e.getKey());
                }
            }
        }
        // v0.5.4 反馈②：单字反向索引（保留首条）
        // v0.5.23 改造：单字全码索引（4码/最长优先——拼词取前2码与 MRU 编码匹配需全码）
        if (singleIndex != null) {
            for (Map.Entry<String, List<String>> e : singleIndex.entrySet()) {
                String code = e.getKey();
                for (String w : e.getValue()) {
                    if (w.length() == 1) {
                        String old = singleCodeIndex.get(w);
                        if (old == null || code.length() >= old.length()) singleCodeIndex.put(w, code);
                    }
                }
            }
        }
        // v0.5.23 动态拼词：前缀索引 + 全码索引（基础库=全码+简码行）
        if (singleIndex != null && prefixIndex == null) {
            prefixIndex = new HashMap<>();
            fullCodeIndex = new HashMap<>();
            for (Map.Entry<String, List<String>> e : singleIndex.entrySet()) {
                String code = e.getKey();
                int clen = code.length();
                // 前缀桶（1-3码；遍历序=编码序，精确码先入桶自然靠前）
                for (int p = 1; p <= clen && p <= 3; p++) {
                    String pre = code.substring(0, p);
                    List<String> list = prefixIndex.get(pre);
                    if (list == null) { list = new ArrayList<>(); prefixIndex.put(pre, list); }
                    for (String w : e.getValue()) {
                        if (!list.contains(w)) list.add(w);
                    }
                }
                // 全码索引（最长优先）
                for (String w : e.getValue()) {
                    String old = fullCodeIndex.get(w);
                    if (old == null || clen >= old.length()) fullCodeIndex.put(w, code);
                }
            }
        }
    }

    /** v0.5.13 反馈②：含指定字的积极成语/搭配（联想启发层，读 associate.txt 列表含字匹配） */
    public static List<String> queryIdioms(String ch) {
        List<String> out = new ArrayList<>();
        if (ch == null || ch.isEmpty()) return out;
        List<String> list = phraseIndex == null ? null : phraseIndex.get(ASSOC_KEY);
        if (list != null) {
            for (String w : list) {
                if (w.indexOf(ch) >= 0 && !w.equals(ch)) out.add(w);
            }
        }
        return out;
    }

    /** v0.5.13：纯文本搭配词表加载（每行一个词组，无编码——进 phraseIndex 特殊键供前缀遍历命中） */
    private static void loadAssetText(Context ctx, String name, Map<String, List<String>> map) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open(name), "UTF-8"))) {
            String line;
            List<String> list = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) list.add(line);
            }
            if (!list.isEmpty()) map.put("zzzz", list);
        } catch (Exception ignored) { }
    }

    private static void loadAsset(Context ctx, String name, Map<String, List<String>> map) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open(name), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // v0.4.9 紧凑格式：前缀连续 a-y 字母为编码，其余为候选（多候选空格分隔）
                int i = 0;
                int len = line.length();
                while (i < len) {
                    char c = line.charAt(i);
                    if (c >= 'a' && c <= 'y') {
                        i++;
                    } else {
                        break;
                    }
                }
                if (i <= 0 || i >= len) continue;
                String code = line.substring(0, i);
                String rest = line.substring(i).trim();
                if (code.length() > 4 || rest.isEmpty()) continue;
                for (String w : rest.split("\\s+")) {
                    if (w.isEmpty()) continue;
                    List<String> list = map.get(code);
                    if (list == null) {
                        list = new ArrayList<>();
                        map.put(code, list);
                    }
                    list.add(w);
                }
            }
        } catch (Exception ignored) { }
    }

    private static void ensureIndex() {
        if (singleIndex == null) {
            singleIndex = new HashMap<>();
            phraseIndex = new HashMap<>();
        }
    }

    /** 3码预测：预测第 4 码高频词组（扫描 4 码词组前缀） */
    public static List<String> queryPredict(String code3) {
        if (code3 == null || code3.length() != 3) return null;
        ensureIndex();
        List<String> result = new ArrayList<>();
        if (phraseIndex != null) {
            for (Map.Entry<String, List<String>> e : phraseIndex.entrySet()) {
                String c = e.getKey();
                if (c.length() == 4 && c.startsWith(code3)) {
                    result.addAll(e.getValue());
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 字后联想（v0.4.8）：上屏某字后，返回本地词库中含该字的词组（最多 12 条）。
     * 用于「打出任意一个字时，自动显示最近词组 + 带此字的词组」。
     */
    public static List<String> queryByChar(String ch) {
        if (ch == null || ch.length() != 1) return null;
        ensureIndex();
        List<String> result = new ArrayList<>();
        if (phraseIndex != null) {
            for (List<String> list : phraseIndex.values()) {
                if (list == null) continue;
                for (String w : list) {
                    if (w.indexOf(ch) >= 0 && !result.contains(w)) {
                        result.add(w);
                        if (result.size() >= 12) return result;
                    }
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 前缀联想（v0.4.9 连续联想）：上屏"陈胜"后，返回本地词库中以该串开头的词组
     * （如"陈胜"→"陈胜吴广"），实现历史事件/顺承式联想。
     */
    public static List<String> queryByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.length() > 4) return null;
        ensureIndex();
        List<String> result = new ArrayList<>();
        if (phraseIndex != null) {
            for (List<String> list : phraseIndex.values()) {
                if (list == null) continue;
                for (String w : list) {
                    if (w.startsWith(prefix) && !result.contains(w)) {
                        result.add(w);
                        if (result.size() >= 10) return result;
                    }
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 候选查询（用户固化的排序规则）：
     *   1码 → 单字；2码 → 单字在前、二字词在后；3码 → 单字；
     *   4码 → 词组优先、单字殿后
     */
    // v0.5.16 反馈③：常用字频序（高频在前；近似《现代汉语常用字表》高频字序，模拟数据，后续用真实语料替换）
    private static final String COMMON_FREQ =
            "的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只没结解问意建月公无系军很情者最立代想已通并提直题党程展五果料象员革位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处队南给色光门即保治北造百规热领七海口东导器压志世金增争济阶油思术极交受联什认六共权收证改清己美再采转更单风切打白教速花带安场身车例真务具万每目至达走积示议声报斗完类八离华名确才科张信马节话米整空元况今集温传土许步群广石记需段研界拉林律叫且究观越织装影算低持音众书布复容儿须际商非验连断深难近矿千周委素技备半办青省列习响约支般史感劳便团往酸历市克何除消构府称太准精值号率族维划选标写存候毛亲快效斯院查江型眼王按格养易置派层片始却专状育厂京识适属圆包火住调满县局照参红细引听该铁价严龙飞";

    /** v0.5.16 反馈③：同码常用字按频序稳定排序（如 dg 研厂三 → 三 在前；不在频序的保持原序靠后） */
    private static void sortByFreq(List<String> list) {
        for (int i = 1; i < list.size(); i++) {
            String k = list.get(i);
            int j = i - 1;
            int fk = COMMON_FREQ.indexOf(k);
            while (j >= 0 && COMMON_FREQ.indexOf(list.get(j)) > fk) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, k);
        }
    }

    /**
     * v0.5.23 动态拼词（钟总核心思路）：基础库 + 86 规则 → 词组无限
     * 输入 4 码 → 2+2 二字词 / 1+1+2 三字词 / 1+1+1+1 四字词 拆码反查组合
     * 保障词置顶；候选按字频（COMMON_FREQ）稳定排序，最多 15 条
     */
    public static List<String> buildDynamicWords(String code) {
        if (code == null || code.length() != 4) return null;
        ensureIndex();
        List<String> out = new ArrayList<>();
        // 保障词精确置顶
        for (String g : GUARANTEED) {
            if (g.length() == 6 && g.startsWith(code)) {
                String w = g.substring(4);
                if (!out.contains(w)) out.add(w);
            }
        }
        // v0.5.24 修复①：拼词桶按字频排序——常用全码字（意/以等）不被同前缀生僻字挤掉（乐意 qiuj / 可以 skny）
        List<String> a2 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(0, 2)));
        List<String> b2 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(2, 4)));
        if (a2 != null) sortByFreq(a2);
        if (b2 != null) sortByFreq(b2);
        if (a2 != null && b2 != null) {
            int la = Math.min(a2.size(), 4), lb = Math.min(b2.size(), 4);
            for (int i = 0; i < la; i++) {
                for (int j = 0; j < lb; j++) {
                    String w = a2.get(i) + b2.get(j);
                    if (!out.contains(w)) out.add(w);
                }
            }
        }
        // 1+1+2 三字词
        List<String> c1 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(0, 1)));
        List<String> c2 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(1, 2)));
        List<String> c3 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(2, 4)));
        if (c1 != null) sortByFreq(c1);
        if (c2 != null) sortByFreq(c2);
        if (c3 != null) sortByFreq(c3);
        if (c1 != null && c2 != null && c3 != null) {
            int l1 = Math.min(c1.size(), 3), l2 = Math.min(c2.size(), 3), l3 = Math.min(c3.size(), 3);
            for (int i = 0; i < l1; i++) {
                for (int j = 0; j < l2; j++) {
                    for (int k = 0; k < l3; k++) {
                        String w = c1.get(i) + c2.get(j) + c3.get(k);
                        if (!out.contains(w)) out.add(w);
                    }
                }
            }
        }
        // 1+1+1+1 四字词（限流 2×2×2×2=16，排序后截断）
        List<String> d1 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(0, 1)));
        List<String> d2 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(1, 2)));
        List<String> d3 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(2, 3)));
        List<String> d4 = prefixIndex == null ? null : new ArrayList<>(prefixIndex.get(code.substring(3, 4)));
        if (d1 != null) sortByFreq(d1);
        if (d2 != null) sortByFreq(d2);
        if (d3 != null) sortByFreq(d3);
        if (d4 != null) sortByFreq(d4);
        if (d1 != null && d2 != null && d3 != null && d4 != null) {
            int l1 = Math.min(d1.size(), 2), l2 = Math.min(d2.size(), 2), l3 = Math.min(d3.size(), 2), l4 = Math.min(d4.size(), 2);
            for (int i = 0; i < l1; i++) {
                for (int j = 0; j < l2; j++) {
                    for (int k = 0; k < l3; k++) {
                        for (int m = 0; m < l4; m++) {
                            String w = d1.get(i) + d2.get(j) + d3.get(k) + d4.get(m);
                            if (!out.contains(w)) out.add(w);
                        }
                    }
                }
            }
        }
        // 字频稳定排序（保障词已在最前；同码重排用 COMMON_FREQ）
        if (out.size() > GUARANTEED.length) {
            List<String> head = new ArrayList<>(out.subList(0, Math.min(GUARANTEED.length, out.size())));
            List<String> tail = new ArrayList<>(out.subList(Math.min(GUARANTEED.length, out.size()), out.size()));
            sortByFreq(tail);
            out = new ArrayList<>(head);
            out.addAll(tail);
        }
        if (out.size() > 15) out = new ArrayList<>(out.subList(0, 15));
        return out;
    }

    /**
     * v0.5.23 动态词组编码（MRU 置顶用）：按 86 规则从单字全码反算词编码
     * 2字=前2+前2；3字=1+1+2；4字=1+1+1+1；仅支持 2-4 字
     */
    public static String dynamicPhraseCode(String word) {
        if (word == null) return null;
        int n = word.length();
        if (n < 2 || n > 4) return null;
        ensureIndex();
        StringBuilder sb = new StringBuilder();
        try {
            if (n == 2) {
                for (int i = 0; i < 2; i++) {
                    String fc = fullCodeIndex == null ? null : fullCodeIndex.get(String.valueOf(word.charAt(i)));
                    if (fc == null) return null;
                    sb.append(fc, 0, Math.min(2, fc.length()));
                }
            } else if (n == 3) {
                for (int i = 0; i < 2; i++) {
                    String fc = fullCodeIndex == null ? null : fullCodeIndex.get(String.valueOf(word.charAt(i)));
                    if (fc == null) return null;
                    sb.append(fc.charAt(0));
                }
                String fc = fullCodeIndex == null ? null : fullCodeIndex.get(String.valueOf(word.charAt(2)));
                if (fc == null) return null;
                sb.append(fc, 0, Math.min(2, fc.length()));
            } else {
                for (int i = 0; i < 4; i++) {
                    String fc = fullCodeIndex == null ? null : fullCodeIndex.get(String.valueOf(word.charAt(i)));
                    if (fc == null) return null;
                    sb.append(fc.charAt(0));
                }
            }
        } catch (Exception ex) { return null; }
        return sb.length() == 4 ? sb.toString() : null;
    }

    /** v0.4.8 反馈①②③：查候选（1 码简码单字 / 2 码先单后词 / 3 码单字+预测 / 4 码词组优先） */
    public static List<String> query(String code) {
        if (code == null || code.isEmpty()) return null;
        ensureIndex();
        List<String> result = new ArrayList<>();
        List<String> singles = singleIndex == null ? null : singleIndex.get(code);
        List<String> phr = phraseIndex == null ? null : phraseIndex.get(code);
        int len = code.length();
        // v0.5.16 反馈③：2/3/4 码单字按常用字频序重排（高频字自动前移，先科学后先进）
        if (len >= 2 && singles != null && singles.size() > 1) sortByFreq(singles);
        if (len == 1) {
            if (singles != null) result.addAll(singles);
        } else if (len == 2) {
            if (singles != null) result.addAll(singles);
            if (phr != null) result.addAll(phr);
        } else if (len == 3) {
            if (singles != null) result.addAll(singles);
        } else if (len == 4) {
            if (phr != null) result.addAll(phr);
            if (singles != null) result.addAll(singles);
        }
        return result.isEmpty() ? null : result;
    }

    /** v0.5.0 反馈②：取词组的五笔编码（反向索引首条），无则返回 null */
    public static String phraseCode(String word) {
        if (word == null || word.isEmpty()) return null;
        ensureIndex();
        if (phraseCodeIndex == null) return null;
        return phraseCodeIndex.get(word);
    }

    /** v0.5.4 反馈②：取单字的五笔编码（反向索引首条），无则返回 null */
    public static String singleCode(String ch) {
        if (ch == null || ch.length() != 1) return null;
        ensureIndex();
        if (singleCodeIndex == null) return null;
        return singleCodeIndex.get(ch);
    }
}
