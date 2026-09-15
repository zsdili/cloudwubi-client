/*
 * dict_conv.c —— 云五笔 Windows 助手 · 云端词库 → 微软五笔用户词典
 * 输入：云端词库文本（每行：编码 词语 [频率]），与 Android/SCF 同源格式
 * 输出：微软五笔"用户定义的单词"导入 txt（每行：编码 词语；UTF-16 LE 由 UI 层写文件）
 * 纯 C 实现，Linux 沙盒可测转换逻辑
 * 编译：gcc -o dict_test dict_conv.c && ./dict_test
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

/* 解析一行：编码 词语 [频率] 或 无空格 编码词语（如 aahw工具）。成功返回1 */
static int parse_line(const char *line, char *code, int clen, char *word, int wlen) {
    const char *p = line;
    while (*p == ' ' || *p == '\t') p++;
    if (!*p || *p == '#') return 0;              /* 空行/注释 */
    /* 编码段 = 开头字母序列 */
    const char *c0 = p;
    while (*p && isalpha((unsigned char)*p)) p++;
    int cn = (int)(p - c0);
    if (cn <= 0 || cn >= clen) return 0;
    memcpy(code, c0, cn); code[cn] = 0;
    /* 词段：有空白 → 下一字段；无空白 → 剩余全部 */
    const char *w0; int wn;
    if (*p == ' ' || *p == '\t') {
        while (*p == ' ' || *p == '\t') p++;
        w0 = p;
        while (*p && !isspace((unsigned char)*p)) p++;
        wn = (int)(p - w0);
    } else {
        w0 = p;
        while (*p && *p != '\n' && *p != '\r') p++;
        wn = (int)(p - w0);
    }
    if (wn <= 0 || wn >= wlen) return 0;
    memcpy(word, w0, wn); word[wn] = 0;
    return 1;
}

/* 转换：输入流 → 输出流；返回输出行数 */
static long convert(FILE *in, FILE *out, char *errbuf, int errlen) {
    char line[512], code[16], word[128];
    long n = 0, dups = 0;
    /* 简单去重：同编码同词只保留一次（用前一条做游标即可；大词库由 UI 层哈希去重） */
    char lastc[16] = "", lastw[128] = "";
    while (fgets(line, sizeof line, in)) {
        if (!parse_line(line, code, sizeof code, word, sizeof word)) continue;
        if (strcmp(code, lastc) == 0 && strcmp(word, lastw) == 0) { dups++; continue; }
        strcpy(lastc, code); strcpy(lastw, word);
        fprintf(out, "%s\t%s\n", code, word);
        n++;
    }
    if (errbuf) snprintf(errbuf, errlen, "去重跳过 %ld 条", dups);
    return n;
}

/* 沙盒自测 */
static int g_pass = 0, g_fail = 0;
static void check_parse(const char *line, int expect, const char *exp_code, const char *exp_word) {
    char code[16] = {0}, word[128] = {0};
    int ok = parse_line(line, code, sizeof code, word, sizeof word);
    if (ok != expect) { g_fail++; printf("FAIL: 解析[%s] 期望%d 实际%d\n", line, expect, ok); return; }
    if (expect && (strcmp(code, exp_code) || strcmp(word, exp_word))) {
        g_fail++; printf("FAIL: 解析[%s] 得 %s|%s 期望 %s|%s\n", line, code, word, exp_code, exp_word); return;
    }
    g_pass++;
}

int main(void) {
    /* 行解析用例 */
    check_parse("g 一", 1, "g", "一");
    check_parse("fwf 二", 1, "fwf", "二");
    check_parse("ggll 王旁青头兼五一", 1, "ggll", "王旁青头兼五一");
    check_parse("   uefj 前进 1000", 1, "uefj", "前进");
    check_parse("kwwl 中华人民共和国 999", 1, "kwwl", "中华人民共和国");
    check_parse("# 注释行", 0, "", "");
    check_parse("", 0, "", "");
    check_parse("   ", 0, "", "");
    check_parse("1234 数字开头", 0, "", "");
    check_parse("ab 有 多余 字段", 1, "ab", "有");   /* 词后多余字段并入词语尾部? 以首个空白后整段为词 */
    check_parse("abcdefghijklmnop 超长编码", 0, "", ""); /* 编码超长拒绝 */
    /* 无空格格式（Android assets 同源：aahw工具） */
    check_parse("aahw工具", 1, "aahw", "工具");
    check_parse("aaog工业", 1, "aaog", "工业");
    check_parse("g一", 1, "g", "一");
    check_parse("tudg科学原理", 1, "tudg", "科学原理");

    /* 转换用例：内存文件 */
    FILE *in = tmpfile(), *out = tmpfile();
    fputs("g 一 100\nfwf 二 200\ng 一\n\n# 注释\ng 一 50\nkwwl 中华人民共和国 999\n", in);
    rewind(in);
    char err[64] = {0};
    long n = convert(in, out, err, sizeof err);
    rewind(out);
    char line[512]; int lines = 0;
    while (fgets(line, sizeof line, out)) lines++;
    if (n == 4 && lines == 4) { g_pass++; }
    else { g_fail++; printf("FAIL: 转换 期望4行 实际%ld行(文件%d行) %s\n", n, lines, err); }

    printf("\n===== 词库转换测试: %d 通过 / %d 失败 / 共 %d =====\n", g_pass, g_fail, g_pass + g_fail);
    return g_fail == 0 ? 0 : 1;
}
