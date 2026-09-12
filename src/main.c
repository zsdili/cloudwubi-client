/*
 * main.c - CloudWubi 端侧内核命令行程序 v0.2.0
 *
 * v0.2.0 新增（阶段5）：
 *   1. 构词查询：自动附加 "phrase":true（3码时触发第4码预测）
 *   2. 多候选选择：候选编号显示，用户输入数字选中
 *   3. 选词学习：用户选中后上报 {"learn":"词组"}（云端 MRU 置顶）
 *   4. 词组显示：解析 "phrases" 数组（词库优先的权威词组）
 *
 * 流程：
 *   1. 读取用户输入的五笔编码
 *   2. 编码合法性校验（1~4 码 a~y）
 *   3. 先查本地 LRU 缓存（用户历史选中）
 *   4. 联网：POST 到云端网关（构词查询）
 *   5. 断网/失败：查本地兜底一级简码
 *   6. 显示候选（编号列表），用户选择后上报 learn
 *
 * 真正的多端输入法宿主（macOS/Android/鸿蒙等）会替换 main，
 * 复用同一套 wubi_engine/lru_cache/http_client/json_parser 模块。
 */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include "wubi_engine.h"
#include "lru_cache.h"
#include "http_client.h"
#include "json_parser.h"

/* 云端网关配置（部署后替换为真实地址） */
#define GATEWAY_HOST "127.0.0.1"
#define GATEWAY_PORT 8000
#define GATEWAY_PATH "/wubi/query"

/* 词组缓冲区大小（UTF-8 四字词 = 12 字节 + 结束符） */
#define PHRASE_BUF_W 16
#define MAX_PHRASES  8

/* UTF-8 编码辅助：把 Unicode 码点编码成 UTF-8 字节流 */
static int utf8_encode(uint32_t cp, char out[5])
{
    if (cp < 0x80) {
        out[0] = (char)cp;
        out[1] = '\0';
        return 1;
    } else if (cp < 0x800) {
        out[0] = (char)(0xC0 | (cp >> 6));
        out[1] = (char)(0x80 | (cp & 0x3F));
        out[2] = '\0';
        return 2;
    } else if (cp < 0x10000) {
        out[0] = (char)(0xE0 | (cp >> 12));
        out[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
        out[2] = (char)(0x80 | (cp & 0x3F));
        out[3] = '\0';
        return 3;
    }
    return 0;
}

static void print_usage(void)
{
    printf("CloudWubi 云五笔 v0.2.0 - 端侧内核演示\n");
    printf("输入 1~4 位五笔编码（a~y）查询；输入数字选择候选；输入 q 退出\n");
    printf("3 码输入自动预测第 4 码高频词组；选词自动上报云端学习（越用越准）\n");
    printf("示例：wqvb -> 你好（词组）  fyt -> 预测 云计算\n");
}

/* 上报用户选词（云端 MRU 学习） */
static void report_learn(const char *phrase)
{
    char body[128];
    char response[1024];

    snprintf(body, sizeof(body), "{\"learn\":\"%s\"}", phrase);
    if (http_post(GATEWAY_HOST, GATEWAY_PORT, GATEWAY_PATH,
                  body, response, sizeof(response)) == 0) {
        printf("  ↳ 已学习: %s（下次优先置顶）\n", phrase);
    } else {
        printf("  ↳ 学习上报失败（离线模式，本地已记录）\n");
    }
}

int main(void)
{
    char line[64];
    char code[WUBI_CODE_BUF];
    size_t len;
    uint32_t unicode;
    char utf8[5];
    char body[160];
    char response[4096];
    uint32_t candidates[WUBI_MAX_CAND];
    size_t cand_count;
    static char phrases[MAX_PHRASES][PHRASE_BUF_W];
    size_t phrase_count;
    size_t i;
    int choice;

    lru_init();
    print_usage();

    while (1) {
        printf("\n> ");
        if (fgets(line, sizeof(line), stdin) == NULL) break;

        /* 去掉换行 */
        len = strlen(line);
        while (len > 0 && (line[len-1] == '\n' || line[len-1] == '\r')) {
            line[--len] = '\0';
        }

        if (len == 0) continue;
        if (strcmp(line, "q") == 0 || strcmp(line, "quit") == 0) break;

        /* 支持数字选择候选（仅选中后状态） */
        if (len == 1 && line[0] >= '0' && line[0] <= '9') {
            choice = line[0] - '0';
            if (choice < (int)phrase_count) {
                printf("选中: %s\n", phrases[choice]);
                report_learn(phrases[choice]);
            } else if (choice < (int)cand_count) {
                utf8_encode(candidates[choice], utf8);
                printf("选中: %s\n", utf8);
                report_learn(utf8);
            } else {
                printf("候选编号越界\n");
            }
            continue;
        }

        /* 1) 编码合法性校验 */
        if (!wubi_code_valid(line, len)) {
            printf("编码非法：需 1~4 位小写字母 a~y\n");
            continue;
        }
        strncpy(code, line, WUBI_CODE_BUF - 1);
        code[WUBI_CODE_BUF - 1] = '\0';

        /* 2) 先查本地 LRU 缓存（用户历史选中的字） */
        if (lru_get(code, &unicode)) {
            utf8_encode(unicode, utf8);
            printf("候选（缓存）: %s\n", utf8);
            continue;
        }

        /* 3) 联网查询云端网关（v0.2.0：构词查询 + 3码预测） */
        cand_count = 0;
        phrase_count = 0;
        if (len <= 3) {
            /* 1~3 码触发构词/预测 */
            snprintf(body, sizeof(body), "{\"code\":\"%s\",\"phrase\":true}", code);
        } else {
            /* 4 码：单字 + 词库词组 */
            snprintf(body, sizeof(body), "{\"code\":\"%s\",\"phrase\":true}", code);
        }

        if (http_post(GATEWAY_HOST, GATEWAY_PORT, GATEWAY_PATH,
                      body, response, sizeof(response)) == 0) {
            cand_count = json_parse_candidates(response, candidates, WUBI_MAX_CAND);
            phrase_count = json_parse_phrases(response, phrases, MAX_PHRASES);

            /* 显示词组候选（词库优先，编号在前） */
            if (phrase_count > 0) {
                printf("词组候选: ");
                for (i = 0; i < phrase_count; i++) {
                    printf("%zu.%s ", i, phrases[i]);
                }
                printf("\n  （输入数字选中，自动上报学习）\n");
            }
            /* 显示单字候选 */
            if (cand_count > 0) {
                printf("单字候选: ");
                for (i = 0; i < cand_count && i < 8; i++) {
                    utf8_encode(candidates[i], utf8);
                    printf("%s ", utf8);
                }
                printf("\n");
            }
            if (cand_count == 0 && phrase_count == 0) {
                printf("无匹配候选\n");
            }
            continue;
        }

        /* 4) 断网/失败，查本地兜底一级简码 */
        if (wubi_fallback_query(code, &unicode)) {
            utf8_encode(unicode, utf8);
            printf("候选（本地兜底）: %s\n", utf8);
            continue;
        }

        printf("无匹配候选\n");
    }

    return 0;
}
