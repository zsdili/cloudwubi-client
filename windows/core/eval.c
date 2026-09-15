/*
 * eval.c —— 云五笔 Windows 助手 · 表达式计算核心
 * 支持：+ - * / 括号、整数/小数、连续计算（1+2=3 后 +4 → 12 语义由 UI 层拼接）
 * 纯 C 实现，Linux/macOS/Windows 均可编译，便于沙盒测试
 * 编译：gcc -o eval_test eval.c && ./eval_test
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <math.h>

/* ---- 表达式求值（递归下降） ---- */
typedef struct { const char *p; int err; } Parser;

static double expr(Parser *ps);
static void skip_spaces(Parser *ps) { while (*ps->p == ' ') ps->p++; }

static double number(Parser *ps) {
    skip_spaces(ps);
    const char *s = ps->p;
    int has_dot = 0;
    while (isdigit((unsigned char)*s) || (*s == '.' && !has_dot)) { if (*s == '.') has_dot = 1; s++; }
    if (s == ps->p) { ps->err = 1; return 0; }
    char buf[64]; int n = (int)(s - ps->p);
    if (n >= 64) n = 63;
    memcpy(buf, ps->p, n); buf[n] = 0;
    ps->p = s;
    return strtod(buf, NULL);
}

static double factor(Parser *ps) {
    skip_spaces(ps);
    if (*ps->p == '(') { ps->p++; double v = expr(ps); skip_spaces(ps); if (*ps->p != ')') ps->err = 1; else ps->p++; return v; }
    return number(ps);
}

static double term(Parser *ps) {
    double v = factor(ps);
    for (;;) {
        skip_spaces(ps);
        char c = *ps->p;
        if (c == '*' || c == '/' || c == 'x' || c == 'X') {
            ps->p++;
            double r = factor(ps);
            if (c == '/' && r == 0) { ps->err = 1; return 0; }
            v = (c == '*' || c == 'x' || c == 'X') ? v * r : v / r;
        } else if (c == '%') {
            ps->p++;
            double r = factor(ps);
            if (r == 0) { ps->err = 1; return 0; }
            v = fmod(v, r);
        } else break;
    }
    return v;
}

static double expr(Parser *ps) {
    skip_spaces(ps);
    int unary = 0;
    if (*ps->p == '-') { unary = -1; ps->p++; }
    else if (*ps->p == '+') { unary = 1; ps->p++; }
    double v = term(ps);
    if (unary == -1) v = -v;
    for (;;) {
        skip_spaces(ps);
        char c = *ps->p;
        if (c == '+' || c == '-') {
            ps->p++;
            double r = term(ps);
            v = (c == '+') ? v + r : v - r;
        } else break;
    }
    return v;
}

/* 公开接口：求值，成功返回1并写结果；失败返回0 */
int eval_expression(const char *s, double *out) {
    if (!s || !*s) return 0;
    Parser ps = { s, 0 };
    double v = expr(&ps);
    skip_spaces(&ps);
    if (ps.err || *ps.p != '\0') return 0;
    if (isnan(v) || isinf(v)) return 0;
    *out = v;
    return 1;
}

/* ---- 结果格式化：整数不带小数点；小数最多保留 10 位、去尾零 ---- */
void format_result(double v, char *buf, int buflen) {
    if (v == (long long)v && fabs(v) < 1e15) {
        snprintf(buf, buflen, "%lld", (long long)v);
        return;
    }
    snprintf(buf, buflen, "%.10f", v);
    int n = (int)strlen(buf);
    while (n > 0 && buf[n-1] == '0') n--;
    if (n > 0 && buf[n-1] == '.') n--;
    buf[n] = 0;
}

/* ---- 沙盒自测：>=50 用例 ---- */
static int g_pass = 0, g_fail = 0;
static void t(const char *in, const char *expect) {
    double v; char buf[64] = {0};
    int ok = eval_expression(in, &v);
    if (ok) format_result(v, buf, sizeof buf);
    if (ok && strcmp(buf, expect) == 0) { g_pass++; }
    else { g_fail++; printf("FAIL: %s = %s (期望 %s)\n", in, ok ? buf : "解析失败", expect); }
}
static void terr(const char *in) {
    double v;
    if (!eval_expression(in, &v)) { g_pass++; }
    else { g_fail++; printf("FAIL: %s 应报错却得 %.10g\n", in, v); }
}

int main(void) {
    /* 基础四则 */
    t("1+2", "3"); t("3-1", "2"); t("2*3", "6"); t("8/4", "2");
    t("1+2*3", "7"); t("(1+2)*3", "9"); t("2+3*4-5", "9");
    t("10-2-3", "5"); t("7/2", "3.5"); t("0.5*4", "2");
    /* 小数与括号 */
    t("1.5+2.5", "4"); t("(2.5+1.5)/2", "2"); t("3.3*3", "9.9");
    t("100/8", "12.5"); t("0.1+0.2", "0.3"); t("10*(2+3)", "50");
    /* 乘除 x 与 % */
    t("2x3", "6"); t("2X4", "8"); t("10%3", "1"); t("17%5", "2");
    /* 混合长式 */
    t("1+2+3+4+5", "15"); t("2*3+4*5", "26"); t("(1+2)*(3+4)", "21");
    t("100-50/2", "75"); t("8/2*4", "16"); terr("2^3"); /* 幂未支持应报错 */
    t("((2+3)*4)", "20"); t("(1+(2*3))-1", "6");
    /* 负数/正号 */
    t("5-8", "-3"); t("-3+5", "2"); t("+5", "5"); t("1-2-3", "-4");
    /* 空格容忍 */
    t(" 1 + 2 ", "3"); t("( 2 + 3 ) * 4", "20");
    /* 大数/精度 */
    t("999+1", "1000"); t("0.1*0.2", "0.02"); t("1/3*3", "1");
    t("12345*6789", "83810205");
    /* 连续计算语义（UI 拼接后求值） */
    t("1+2*4", "9"); t("1+2+3*4", "15"); t("100/10/2", "5");
    /* 错误输入 */
    terr(""); terr("a+b"); terr("1+"); terr("+"); terr("1++2");
    terr("(1+2"); terr("1+2)"); terr("1/0"); terr("2..3"); terr("*5");
    terr("1+2*"); terr("()"); terr("1e5+1"); terr("1 2"); terr("--5");

    printf("\n===== 测试结果: %d 通过 / %d 失败 / 共 %d 用例 =====\n", g_pass, g_fail, g_pass + g_fail);
    return g_fail == 0 ? 0 : 1;
}
