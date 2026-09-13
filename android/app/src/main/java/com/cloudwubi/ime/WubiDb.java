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

    private WubiDb() { }

    /** 由 IME onCreate 调用一次，加载 raw 词库 */
    public static synchronized void init(Context ctx) {
        if (singleIndex != null) return;
        singleIndex = new HashMap<>();
        phraseIndex = new HashMap<>();
        phraseCodeIndex = new HashMap<>();
        singleCodeIndex = new HashMap<>();
        loadRaw(ctx, R.raw.wubi_single, singleIndex);
        loadRaw(ctx, R.raw.wubi_phrase, phraseIndex);
        // 词组反向索引（同一词可能多码，保留首条）
        if (phraseIndex != null) {
            for (Map.Entry<String, List<String>> e : phraseIndex.entrySet()) {
                for (String w : e.getValue()) {
                    if (!phraseCodeIndex.containsKey(w)) phraseCodeIndex.put(w, e.getKey());
                }
            }
        }
        // v0.5.4 反馈②：单字反向索引（保留首条）
        if (singleIndex != null) {
            for (Map.Entry<String, List<String>> e : singleIndex.entrySet()) {
                for (String w : e.getValue()) {
                    if (w.length() == 1 && !singleCodeIndex.containsKey(w)) singleCodeIndex.put(w, e.getKey());
                }
            }
        }
    }

    private static void loadRaw(Context ctx, int resId, Map<String, List<String>> map) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getResources().openRawResource(resId), "UTF-8"))) {
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
    public static List<String> query(String code) {
        if (code == null || code.isEmpty()) return null;
        ensureIndex();
        List<String> result = new ArrayList<>();
        List<String> singles = singleIndex == null ? null : singleIndex.get(code);
        List<String> phr = phraseIndex == null ? null : phraseIndex.get(code);
        int len = code.length();
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
