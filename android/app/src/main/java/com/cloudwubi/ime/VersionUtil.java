package com.cloudwubi.ime;

/**
 * 版本号工具（纯 Java，无 Android 依赖）——线上唯一实现，JVM 单元测试直接测
 * 单元测试：android/tests/VersionUtilTest.java
 */
public class VersionUtil {

    /**
     * 版本号比较（按段数字比较，避免字符串比较陷阱：0.5.9 < 0.5.10）
     * @return a>b → 1；a<b → -1；相等或参数非法 → 0
     */
    public static int compare(String a, String b) {
        if (a == null || b == null) return 0;
        String[] pa = a.replace("v", "").split("\\.");
        String[] pb = b.replace("v", "").split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x;
            int y;
            try {
                x = i < pa.length ? Integer.parseInt(pa[i]) : 0;
                y = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }
}
