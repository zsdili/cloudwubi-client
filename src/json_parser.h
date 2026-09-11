/*
 * json_parser.h - 极简 JSON 解析器
 * CloudWubi client 端侧内核
 *
 * 只解析云端网关返回的固定结构：
 *   {"code":"wq","candidates":[20320,20320]}
 * 提取 "candidates" 数组里的整数（Unicode 码点）。
 * 不引入完整 JSON 库，体积最小。
 */

#ifndef JSON_PARSER_H
#define JSON_PARSER_H

#include <stdint.h>
#include <stddef.h>

/*
 * 从 JSON 字符串中提取 "candidates" 数组的整数元素
 * json    : 输入 JSON 字符串
 * out     : 输出数组（Unicode 码点）
 * out_max : 输出数组容量
 * 返回提取到的元素个数
 */
size_t json_parse_candidates(const char *json, uint32_t *out, size_t out_max);

#endif /* JSON_PARSER_H */
