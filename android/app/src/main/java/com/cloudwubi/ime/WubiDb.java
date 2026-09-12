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

    private WubiDb() { }

    /** 由 IME onCreate 调用一次，加载 raw 词库 */
    public static synchronized void init(Context ctx) {
        if (singleIndex != null) return;
        singleIndex = new HashMap<>();
        phraseIndex = new HashMap<>();
        loadRaw(ctx, R.raw.wubi_single, singleIndex);
        loadRaw(ctx, R.raw.wubi_phrase, phraseIndex);
    }

    private static void loadRaw(Context ctx, int resId, Map<String, List<String>> map) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getResources().openRawResource(resId), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                String code = line.substring(0, sp);
                String w = line.substring(sp + 1).trim();
                if (code.isEmpty() || w.isEmpty()) continue;
                List<String> list = map.get(code);
                if (list == null) {
                    list = new ArrayList<>();
                    map.put(code, list);
                }
                list.add(w);
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
}
