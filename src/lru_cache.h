/*
 * lru_cache.h - 本地微型 LRU 用户缓存
 * CloudWubi client 端侧内核
 *
 * 作用：缓存用户最近选用过的字词（编码 -> Unicode），
 * 减少云端请求次数，提升输入响应速度。
 * 容量极小（固定条目），占用内存几 KB，符合 <800KB 包体约束。
 */

#ifndef LRU_CACHE_H
#define LRU_CACHE_H

#include <stdint.h>
#include <stddef.h>

/* 缓存最大条目数（极小，几十条即可显著命中） */
#define LRU_CAPACITY 64

/* 编码字符串最大长度 */
#define LRU_CODE_LEN 8

/*
 * 缓存条目
 * key   : 五笔编码（可含词组，最长 7 字符 + 结束符）
 * value : 用户最终选定的 Unicode 码点（单个字符）
 */
typedef struct {
    char key[LRU_CODE_LEN];
    uint32_t value;
} LruEntry;

/*
 * 初始化 LRU 缓存（清空所有条目）
 */
void lru_init(void);

/*
 * 查询缓存
 * 命中返回 1 并写入 out；未命中返回 0。
 */
int lru_get(const char *key, uint32_t *out);

/*
 * 写入缓存（用户选中某个候选后调用）
 * 若 key 已存在则更新 value 并提升为最近使用；
 * 若缓存已满，淘汰最久未使用的条目。
 */
void lru_put(const char *key, uint32_t value);

#endif /* LRU_CACHE_H */
