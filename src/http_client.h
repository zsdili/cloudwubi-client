/*
 * http_client.h - 极简 HTTP 客户端
 * CloudWubi client 端侧内核
 *
 * 仅用于向云端网关发送 POST 请求并接收响应。
 * 采用标准 POSIX socket，无第三方库。
 * 支持编译开关：若构建纯离线版本，可定义 CW_NO_HTTP 剔除本模块以进一步瘦身。
 */

#ifndef HTTP_CLIENT_H
#define HTTP_CLIENT_H

#include <stddef.h>

/*
 * 发送 HTTP POST 请求
 * host     : 服务器地址（如 "api.example.com"）
 * port     : 端口（80 或 443）
 * path     : 请求路径（如 "/wubi/query"）
 * body     : 请求体（JSON 字符串）
 * resp_buf : 接收响应缓冲区
 * resp_len : 缓冲区大小
 *
 * 返回 0 = 成功，非 0 = 失败
 */
int http_post(const char *host, int port, const char *path,
              const char *body, char *resp_buf, size_t resp_len);

#endif /* HTTP_CLIENT_H */
