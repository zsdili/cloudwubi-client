/*
 * lru_cache.c - 本地微型 LRU 用户缓存实现
 *
 * 极简数组实现，容量固定（LRU_CAPACITY）。
 * 每次访问/写入，通过数组前移实现"最近使用在前"，
 * 末尾即最久未使用，满时淘汰末尾。
 * 无堆分配，静态数组，内存占用极小。
 */

#include "lru_cache.h"
#include <string.h>

/* 静态存储：所有条目 + 每个条目的访问时间戳（单调递增计数器） */
static LruEntry entries[LRU_CAPACITY];
static unsigned long access_counter[LRU_CAPACITY];
static unsigned long global_tick = 0;

void lru_init(void)
{
    int i;
    for (i = 0; i < LRU_CAPACITY; i++) {
        entries[i].key[0] = '\0';
        entries[i].value = 0;
        access_counter[i] = 0;
    }
    global_tick = 0;
}

int lru_get(const char *key, uint32_t *out)
{
    int i;

    if (key == NULL || out == NULL) return 0;

    global_tick++;

    for (i = 0; i < LRU_CAPACITY; i++) {
        if (entries[i].key[0] != '\0' && strcmp(entries[i].key, key) == 0) {
            access_counter[i] = global_tick;
            *out = entries[i].value;
            return 1;
        }
    }
    return 0;
}

void lru_put(const char *key, uint32_t value)
{
    int i;
    int empty_slot = -1;
    int victim = -1;
    unsigned long oldest = 0;

    if (key == NULL) return;

    global_tick++;

    /* 1) 若 key 已存在，更新并标记为最近使用 */
    for (i = 0; i < LRU_CAPACITY; i++) {
        if (entries[i].key[0] != '\0' && strcmp(entries[i].key, key) == 0) {
            entries[i].value = value;
            access_counter[i] = global_tick;
            return;
        }
    }

    /* 2) 找空位 */
    for (i = 0; i < LRU_CAPACITY; i++) {
        if (entries[i].key[0] == '\0') {
            empty_slot = i;
            break;
        }
    }

    if (empty_slot >= 0) {
        strncpy(entries[empty_slot].key, key, LRU_CODE_LEN - 1);
        entries[empty_slot].key[LRU_CODE_LEN - 1] = '\0';
        entries[empty_slot].value = value;
        access_counter[empty_slot] = global_tick;
        return;
    }

    /* 3) 满则淘汰最久未使用 */
    oldest = access_counter[0];
    victim = 0;
    for (i = 1; i < LRU_CAPACITY; i++) {
        if (access_counter[i] < oldest) {
            oldest = access_counter[i];
            victim = i;
        }
    }

    strncpy(entries[victim].key, key, LRU_CODE_LEN - 1);
    entries[victim].key[LRU_CODE_LEN - 1] = '\0';
    entries[victim].value = value;
    access_counter[victim] = global_tick;
}
