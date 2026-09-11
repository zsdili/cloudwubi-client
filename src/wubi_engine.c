/*
 * wubi_engine.c - 五笔编码引擎实现
 * CloudWubi client 端侧内核
 *
 * 包含：编码校验 + 本地兜底一级简码查询
 * 兜底简码仅用于极端断网场景，不作为主力；完整编码查询走云端网关。
 */

#include "wubi_engine.h"
#include <string.h>
#include <ctype.h>

/* ------------------------------------------------------------------
 * 编码合法性校验
 * 86 五笔使用 a~y 共 25 个键位（z 键保留），最长 4 码。
 * 返回 1 = 合法，0 = 非法
 * ------------------------------------------------------------------ */
int wubi_code_valid(const char *code, size_t len)
{
    size_t i;

    if (code == NULL) return 0;
    if (len < 1 || len > WUBI_MAX_CODE) return 0;

    for (i = 0; i < len; i++) {
        char c = code[i];
        /* 只接受 a~y 小写字母；z 不作为编码位 */
        if (c < 'a' || c > 'y') return 0;
    }
    return 1;
}

/* ------------------------------------------------------------------
 * 本地兜底一级简码表
 * 格式：{ "编码", Unicode码点 }
 * 仅含高频一级简码（每键一个高频字），体积极小（约 1~2KB）。
 * 断网应急可用，联网时优先走云端。
 * ------------------------------------------------------------------ */
typedef struct {
    const char *code;
    uint32_t unicode;
} FallbackEntry;

static const FallbackEntry FALLBACK_TABLE[] = {
    {"g", 0x4E00}, /* 一 */
    {"f", 0x5730}, /* 地 */
    {"d", 0x5728}, /* 在 */
    {"s", 0x8981}, /* 要 */
    {"a", 0x5DE5}, /* 工 */
    {"h", 0x4E0A}, /* 上 */
    {"j", 0x662F}, /* 是 */
    {"k", 0x4E2D}, /* 中 */
    {"l", 0x56FD}, /* 国 */
    {"m", 0x540C}, /* 同 */
    {"t", 0x548C}, /* 和 */
    {"r", 0x7684}, /* 的 */
    {"e", 0x6709}, /* 有 */
    {"w", 0x4EBA}, /* 人 */
    {"q", 0x91D1}, /* 金 */
    {"y", 0x4E3B}, /* 主 */
    {"u", 0x4EA7}, /* 产 */
    {"i", 0x4EE5}, /* 以 */
    {"o", 0x6211}, /* 我 */
    {"p", 0x4E86}, /* 了 */
};

#define FALLBACK_COUNT (sizeof(FALLBACK_TABLE) / sizeof(FallbackEntry))

int wubi_fallback_query(const char *code, uint32_t *out)
{
    size_t i;

    if (code == NULL || out == NULL) return 0;

    for (i = 0; i < FALLBACK_COUNT; i++) {
        if (strcmp(FALLBACK_TABLE[i].code, code) == 0) {
            *out = FALLBACK_TABLE[i].unicode;
            return 1;
        }
    }
    return 0;
}
