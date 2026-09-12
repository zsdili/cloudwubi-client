/*
 * json_parser.h - 极简 JSON 解析器
 * CloudWubi client 端侧内核
 *
 * 解析云端网关返回的固定结构：
 *   {"code":"wq","candidates":[20320,20320]}
 *   {"code":"wqvb","candidates":[...],"phrases":["你好","您好"]}
 * 提取 "candidates" 数组里的整数（Unicode 码点）与 "phrases" 数组里的词组。
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

/*
 * 从 JSON 字符串中提取 "phrases" 数组的字符串元素（词组）
 * json     : 输入 JSON 字符串
 * out      : 输出数组（每个词组 UTF-8 字符串，缓冲区宽 16 字节，支持四字词12字节+结束符）
 * out_max  : 词组数量上限
 * 返回提取到的词组个数
 */
size_t json_parse_phrases(const char *json, char out[][16], size_t out_max);

#endif /* JSON_PARSER_H */
