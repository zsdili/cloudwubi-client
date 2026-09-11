/*
 * main.c - CloudWubi 端侧内核命令行演示程序
 *
 * 流程：
 *   1. 读取用户输入的五笔编码
 *   2. 编码合法性校验
 *   3. 先查本地 LRU 缓存（用户历史选中）
 *   4. 联网：POST 到云端网关获取候选 Unicode
 *   5. 断网/失败：查本地兜底一级简码
 *   6. 打印候选（用 UTF-8 编码输出，示意汉字）
 *
 * 这是命令行演示版，用于验证端到端链路；
 * 真正的多端输入法宿主（Windows/Android/鸿蒙等）会替换 main，
 * 复用同一套 wubi_engine/lru_cache/http_client/json_parser 模块。
 */

#include <stdio.h>
#include <string.h>

#include "wubi_engine.h"
#include "lru_cache.h"
#include "http_client.h"
#include "json_parser.h"

/* 云端网关配置（部署后替换为真实地址） */
#define GATEWAY_HOST "127.0.0.1"
#define GATEWAY_PORT 8000
#define GATEWAY_PATH "/wubi/query"

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
    printf("CloudWubi 云五笔 - 端侧内核演示\n");
    printf("输入 1~4 位五笔编码（a~y），回车查询；输入 q 退出\n");
    printf("示例：wq  -> 你\n");
}

int main(void)
{
    char line[64];
    char code[WUBI_CODE_BUF];
    size_t len;
    uint32_t unicode;
    char utf8[5];
    char body[128];
    char response[2048];
    uint32_t candidates[WUBI_MAX_CAND];
    size_t cand_count;
    size_t i;

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

        /* 1) 编码合法性校验 */
        if (!wubi_code_valid(line, len)) {
            printf("编码非法：需 1~4 位小写字母 a~y\n");
            continue;
        }
        strncpy(code, line, WUBI_CODE_BUF - 1);
        code[WUBI_CODE_BUF - 1] = '\0';

        /* 2) 先查本地 LRU 缓存 */
        if (lru_get(code, &unicode)) {
            utf8_encode(unicode, utf8);
            printf("候选（缓存）: %s\n", utf8);
            continue;
        }

        /* 3) 联网查询云端网关 */
        snprintf(body, sizeof(body), "{\"code\":\"%s\"}", code);
        if (http_post(GATEWAY_HOST, GATEWAY_PORT, GATEWAY_PATH,
                      body, response, sizeof(response)) == 0) {
            cand_count = json_parse_candidates(response, candidates, WUBI_MAX_CAND);
            if (cand_count > 0) {
                printf("候选（云端）: ");
                for (i = 0; i < cand_count; i++) {
                    utf8_encode(candidates[i], utf8);
                    printf("%s ", utf8);
                }
                printf("\n");
                /* 用户若选中第一候选，可 lru_put 记录（演示默认记住首个） */
                lru_put(code, candidates[0]);
                continue;
            }
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
