package com.cloudwubi.ime;

/**
 * 云五笔联想引擎（纯 Java，无 Android 依赖）
 *
 * 真实线上联想逻辑的唯一实现：CloudWubiIME 联想路径调用本类，
 * 禁止在别处另写副本 —— 保证 JVM 单元测试测的就是线上运行的代码。
 * 单元测试：android/tests/AssociateEngineTest.java（javac 编译直接测本类）
 */
public class AssociateEngine {

    /**
     * 联想点选重叠拼接（v0.5.57 反馈：陈胜吴广 + 吴广起义 → 陈胜吴广吴广起义 的根治）：
     * 候选以文本框末尾重叠 → 返回需删除的光标前字符数（0=无重叠不删）
     * 例：tail="陈胜吴广", candidate="吴广起义" → 返回 2（删"吴广"，拼接"陈胜吴广起义"）
     *     tail="宇",        candidate="宇宙"     → 返回 1
     */
    public static int overlapJoin(String tail, String candidate) {
        if (tail == null || candidate == null || candidate.isEmpty() || tail.isEmpty()) return 0;
        int n = Math.min(candidate.length(), tail.length());
        for (; n >= 1; n--) {
            if (tail.endsWith(candidate.substring(0, n))) return n;
        }
        return 0;
    }
}
