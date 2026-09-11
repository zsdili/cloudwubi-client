#!/bin/sh
# compile_size.sh - 一键体积优化编译
# 编译并打印最终二进制体积，验证是否满足 <800KB 约束
#
# 用法：
#   ./compile_size.sh            # 标准体积编译
#   ./compile_size.sh offline    # 离线版本（剔除 HTTP 模块，更小）

set -e

MODE="${1:-normal}"

echo "==> CloudWubi 端侧内核编译（模式: $MODE）"

if [ "$MODE" = "offline" ]; then
    echo "==> 离线模式：剔除 HTTP 模块"
    # 离线版只需 main + engine + cache + json，无 HTTP
    mkdir -p build
    gcc -std=c99 -Os -s -ffunction-sections -fdata-sections \
        -Wl,--gc-sections -fno-stack-protector -Wall -Wextra \
        -DCW_NO_HTTP \
        src/main.c src/wubi_engine.c src/lru_cache.c src/json_parser.c \
        -o cloudwubi_demo_offline
    echo "==> 离线版本:"
    ls -lh cloudwubi_demo_offline
else
    make clean >/dev/null 2>&1 || true
    make
    echo "==> 标准版本:"
    ls -lh cloudwubi_demo
fi

echo ""
echo "==> 体积检查（约束 < 800KB）"
if [ "$MODE" = "offline" ]; then
    SIZE=$(stat -c %s cloudwubi_demo_offline 2>/dev/null || stat -f %z cloudwubi_demo_offline)
else
    SIZE=$(stat -c %s cloudwubi_demo 2>/dev/null || stat -f %z cloudwubi_demo)
fi

if [ -n "$SIZE" ] && [ "$SIZE" -lt 819200 ]; then
    echo "✅ 通过：${SIZE} 字节 = $(echo "scale=2; $SIZE/1024" | bc)KB，小于 800KB"
else
    echo "❌ 未达约束（${SIZE} 字节），需进一步瘦身"
fi
