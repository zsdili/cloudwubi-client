package com.cloudwubi.ime;

import java.util.ArrayList;
import java.util.List;

/**
 * MruEngine - MRU 置顶引擎（纯 Java，线上唯一实现，JVM 可直测）
 * v0.5.61 写死规则：之前打过的字/词必须放在最前面（除非同码新上屏）
 *
 * 修复要点（用户实测"上屏字词从未前移"根因）：
 *   旧逻辑：m 的编码反查（phraseCode/singleCode）必须 == 当前 code 才置顶；
 *           动态词（陈胜/吴广等不在词组文件）/ 云端词反查失败 → 永不置顶。
 *   新逻辑：候选列表"包含 m"即置顶（当前编码能打出它 = 同码可见，置顶天然合理）
 *           ——本地词、动态词、云端词全部命中。
 */
public final class MruEngine {

    private MruEngine() { }

    /**
     * 候选列表 MRU 置顶：lastSelected（最近一个）与 mruList（最近 N 个，时间倒序）
     * 依次移到列表最前；不在候选中的跳过（当前码不可见不置顶）；新上屏在前。
     */
    public static void applyTop(List<String> candidates, String lastSelected, List<String> mruList) {
        if (candidates == null || candidates.isEmpty()) return;
        java.util.List<String> ordered = new ArrayList<>();
        if (lastSelected != null && !lastSelected.isEmpty()) ordered.add(lastSelected);
        if (mruList != null) {
            for (String m : mruList) {
                if (!ordered.contains(m)) ordered.add(m);
            }
        }
        for (String m : ordered) {
            if (!candidates.contains(m)) continue;   // 当前编码不可见 → 不置顶（不插入新词）
            candidates.remove(m);
            int pos = 0;
            int idx = ordered.indexOf(m);
            java.util.List<String> before = ordered.subList(0, idx);
            for (int i = 0; i < candidates.size() && i < idx; i++) {
                if (before.contains(candidates.get(i))) pos = i + 1;
            }
            candidates.add(Math.min(pos, candidates.size()), m);
        }
    }
}
