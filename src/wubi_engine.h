/*
 * wubi_engine.h - 五笔编码引擎头文件
 * CloudWubi client 端侧内核
 *
 * 职责：编码合法性校验、本地兜底简码查询、候选结构定义
 * 设计原则：纯标准 C99，无第三方依赖，平台无关，编译体积最小化
 */

#ifndef WUBI_ENGINE_H
#define WUBI_ENGINE_H

#include <stdint.h>
#include <stddef.h>

/* 五笔编码最大长度（86 五笔最长 4 码） */
#define WUBI_MAX_CODE 4

/* 候选最大数量（单次返回） */
#define WUBI_MAX_CAND 16

/* 编码字符串最大长度（含结束符） */
#define WUBI_CODE_BUF (WUBI_MAX_CODE + 1)

/*
 * 候选条目
 * code     : 五笔编码（如 "wq"）
 * unicode  : 对应汉字 Unicode 码点（如 "你" = 0x4F60）
 */
typedef struct {
    char code[WUBI_CODE_BUF];
    uint32_t unicode;
} WubiCandidate;

/*
 * 校验五笔编码是否合法
 * 合法条件：
 *   - 长度 1~4
 *   - 全部为小写字母 a~y（86 五笔只用 a-y，不用 z）
 *   - 仅接受 ASCII 小写字母
 * 返回 1 = 合法，0 = 非法
 */
int wubi_code_valid(const char *code, size_t len);

/*
 * 本地兜底一级简码查询（断网应急用）
 * 命中则把 unicode 写入 out 并返回 1，否则返回 0
 * 兜底库极小（几十条），保证离线基础单字输入
 */
int wubi_fallback_query(const char *code, uint32_t *out);

#endif /* WUBI_ENGINE_H */
