/*
 * test_engine.c - CloudWubi 端侧内核单元测试
 *
 * 在 CI 中自动运行（make test），验证核心逻辑正确性：
 *  1. 编码合法性校验
 *  2. 本地兜底一级简码
 *  3. LRU 缓存读写与淘汰
 *  4. JSON 解析
 *
 * 编译：gcc -std=c99 -O2 -o test_engine test_engine.c wubi_engine.c lru_cache.c json_parser.c
 * 运行：./test_engine   返回 0 = 全部通过；非 0 = 有失败
 */

#include <stdio.h>
#include <string.h>

#include "wubi_engine.h"
#include "lru_cache.h"
#include "json_parser.h"

static int passed = 0;
static int failed = 0;

#define CHECK(cond, name) \
    do { \
        if (cond) { passed++; printf("  ✅ %s\n", name); } \
        else { failed++; printf("  ❌ %s\n", name); } \
    } while (0)

static void test_code_valid(void)
{
    printf("[测试1] 编码合法性校验\n");
    CHECK(wubi_code_valid("g", 1) == 1, "单码 g 合法");
    CHECK(wubi_code_valid("wq", 2) == 1, "双码 wq 合法");
    CHECK(wubi_code_valid("gggg", 4) == 1, "四码 gggg 合法");
    CHECK(wubi_code_valid("", 0) == 0, "空编码非法");
    CHECK(wubi_code_valid("z", 1) == 0, "z 不在 a~y，非法");
    CHECK(wubi_code_valid("ggggg", 5) == 0, "超过4码非法");
    CHECK(wubi_code_valid("G", 1) == 0, "大写非法");
}

static void test_fallback(void)
{
    uint32_t out = 0;
    printf("[测试2] 本地兜底一级简码\n");
    CHECK(wubi_fallback_query("w", &out) == 1 && out == 0x4EBA, "w -> 人(0x4EBA)");
    CHECK(wubi_fallback_query("g", &out) == 1 && out == 0x4E00, "g -> 一(0x4E00)");
    CHECK(wubi_fallback_query("wq", &out) == 0, "wq 无兜底(不在表内)");
    CHECK(wubi_fallback_query("zz", &out) == 0, "非法编码返回0");
}

static void test_lru(void)
{
    uint32_t out = 0;
    printf("[测试3] LRU缓存\n");
    lru_init();
    CHECK(lru_get("wq", &out) == 0, "空缓存未命中");

    lru_put("wq", 0x4F60);
    CHECK(lru_get("wq", &out) == 1 && out == 0x4F60, "写入后命中 wq=0x4F60");

    lru_put("gggg", 0x738B);
    CHECK(lru_get("gggg", &out) == 1, "写入第二个key命中");

    /* 覆盖已有key */
    lru_put("wq", 0x60A8);
    CHECK(lru_get("wq", &out) == 1 && out == 0x60A8, "覆盖wq生效");
}

static void test_json(void)
{
    uint32_t out[8];
    size_t n;
    printf("[测试4] JSON解析\n");

    n = json_parse_candidates("{\"code\":\"wq\",\"candidates\":[20320,20320]}", out, 8);
    CHECK(n == 2 && out[0] == 20320 && out[1] == 20320, "解析 candidates 数组");

    n = json_parse_candidates("{\"candidates\":[1,2,3,4,5]}", out, 8);
    CHECK(n == 5, "解析5个元素");

    n = json_parse_candidates("{\"candidates\":[]}", out, 8);
    CHECK(n == 0, "空数组解析0个");

    n = json_parse_candidates("no json here", out, 8);
    CHECK(n == 0, "非法JSON返回0");
}

int main(void)
{
    printf("=== CloudWubi 端侧内核单元测试 ===\n\n");

    test_code_valid();
    test_fallback();
    test_lru();
    test_json();

    printf("\n=== 结果: %d 通过, %d 失败 ===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
