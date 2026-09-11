/*
 * http_client.c - 极简 HTTP 客户端实现
 *
 * 基于 POSIX socket，仅实现最基本的 HTTP/1.1 POST。
 * 支持编译开关 CW_NO_HTTP：离线版本可完全剔除本文件。
 */

#include "http_client.h"

#ifndef CW_NO_HTTP

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>

int http_post(const char *host, int port, const char *path,
              const char *body, char *resp_buf, size_t resp_len)
{
    int sock = -1;
    struct hostent *he;
    struct sockaddr_in server;
    char request[2048];
    int req_len;
    ssize_t n;
    size_t total = 0;

    if (host == NULL || path == NULL || body == NULL ||
        resp_buf == NULL || resp_len == 0) {
        return 1;
    }

    /* 解析主机名 */
    he = gethostbyname(host);
    if (he == NULL) return 1;

    /* 创建 socket */
    sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return 1;

    server.sin_family = AF_INET;
    server.sin_port = htons((unsigned short)port);
    memcpy(&server.sin_addr, he->h_addr_list[0], he->h_length);

    if (connect(sock, (struct sockaddr *)&server, sizeof(server)) < 0) {
        close(sock);
        return 1;
    }

    /* 构造请求 */
    req_len = snprintf(request, sizeof(request),
        "POST %s HTTP/1.1\r\n"
        "Host: %s\r\n"
        "Content-Type: application/json\r\n"
        "Content-Length: %zu\r\n"
        "Connection: close\r\n"
        "\r\n"
        "%s",
        path, host, strlen(body), body);

    if (req_len <= 0 || (size_t)req_len >= sizeof(request)) {
        close(sock);
        return 1;
    }

    if (send(sock, request, (size_t)req_len, 0) < 0) {
        close(sock);
        return 1;
    }

    /* 读取响应 */
    while (total < resp_len - 1) {
        n = recv(sock, resp_buf + total, resp_len - 1 - total, 0);
        if (n <= 0) break;
        total += (size_t)n;
    }
    resp_buf[total] = '\0';

    close(sock);
    return 0;
}

#endif /* CW_NO_HTTP */
