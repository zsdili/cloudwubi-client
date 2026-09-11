/*
 * json_parser.c - 极简 JSON 解析器实现
 *
 * 仅解析 {"candidates":[整数,...]} 数组。
 * 算法：定位 "candidates" 字段后，扫描 '[' 与 ']' 之间的整数。
 */

#include "json_parser.h"
#include <string.h>
#include <ctype.h>

static const char *find_key(const char *json, const char *key)
{
    const char *p;

    if (json == NULL || key == NULL) return NULL;

    p = json;
    while ((p = strstr(p, key)) != NULL) {
        /* 确保 key 前是引号，即 "key" 字段 */
        if (p > json && *(p - 1) == '"') {
            return p;
        }
        p++;
    }
    return NULL;
}

size_t json_parse_candidates(const char *json, uint32_t *out, size_t out_max)
{
    const char *key_pos;
    const char *p;
    const char *end;
    size_t count = 0;
    long val;

    if (json == NULL || out == NULL || out_max == 0) return 0;

    key_pos = find_key(json, "candidates");
    if (key_pos == NULL) return 0;

    /* 找到 '[' */
    p = strchr(key_pos, '[');
    if (p == NULL) return 0;
    p++;

    end = strchr(p, ']');
    if (end == NULL) return 0;

    while (p < end && count < out_max) {
        /* 跳过空白与逗号 */
        while (p < end && (*p == ' ' || *p == ',' || *p == '\t')) p++;

        if (p >= end) break;

        /* 解析整数 */
        val = 0;
        while (p < end && *p >= '0' && *p <= '9') {
            val = val * 10 + (*p - '0');
            p++;
        }
        out[count] = (uint32_t)val;
        count++;
    }

    return count;
}
