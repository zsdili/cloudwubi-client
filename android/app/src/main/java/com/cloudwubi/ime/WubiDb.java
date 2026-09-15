package com.cloudwubi.ime;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * WubiDb - 云五笔本地五笔86精简词库（Android 壳）
 * 职责：从 assets 读词库行 → 委托 WubiDbCore（纯 Java 线上唯一实现，JVM 可直测）
 * 数据来源：cloudwubi-rules（Rime 官方五笔86码表 LGPL-3.0 派生）
 */
public final class WubiDb {

    private WubiDb() { }

    /** 由 IME onCreate 调用一次，加载词库（v0.5.61：委托 WubiDbCore 构建索引） */
    public static synchronized void init(Context ctx) {
        List<String> singleLines = readAssetLines(ctx, "wubi_single.txt");
        List<String> phraseLines = readAssetLines(ctx, "wubi_phrase.txt");
        WubiDbCore.init(singleLines, phraseLines);
    }

    private static List<String> readAssetLines(Context ctx, String name) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open(name), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } catch (Exception ignored) { }
        return lines;
    }

    public static String simple1Char(char key) { return WubiDbCore.simple1Char(key); }

    public static List<String> queryIdioms(String ch) { return WubiDbCore.queryIdioms(ch); }

    public static List<String> queryPredict(String code3) { return WubiDbCore.queryPredict(code3); }

    public static List<String> queryByChar(String ch) { return WubiDbCore.queryByChar(ch); }

    public static List<String> queryByPrefix(String prefix) { return WubiDbCore.queryByPrefix(prefix); }

    public static List<String> buildDynamicWords(String code) { return WubiDbCore.buildDynamicWords(code); }

    public static String dynamicPhraseCode(String word) { return WubiDbCore.dynamicPhraseCode(word); }

    public static List<String> query(String code) { return WubiDbCore.query(code); }

    public static String phraseCode(String word) { return WubiDbCore.phraseCode(word); }

    public static String singleCode(String ch) { return WubiDbCore.singleCode(ch); }
}
