# Makefile - CloudWubi 端侧内核
# 目标：编译出 < 800KB 的极简二进制
#
# 体积优化核心参数：
#   -Os      : 优化体积
#   -s       : 剥离符号表
#   -ffunction-sections -fdata-sections : 让链接器可剔除未用函数/数据
#   -Wl,--gc-sections                   : 垃圾回收未用段，进一步瘦身
#   -fno-stack-protector                : 关闭栈保护（嵌入式场景可省空间）

CC      ?= gcc
CFLAGS  ?= -std=c99 -Os -s -ffunction-sections -fdata-sections -Wl,--gc-sections -fno-stack-protector -Wall -Wextra
LDFLAGS ?=

SRC_DIR = src
OBJ_DIR = build
TARGET  = cloudwubi_demo

SRCS = $(SRC_DIR)/main.c \
       $(SRC_DIR)/wubi_engine.c \
       $(SRC_DIR)/lru_cache.c \
       $(SRC_DIR)/http_client.c \
       $(SRC_DIR)/json_parser.c

OBJS = $(SRCS:$(SRC_DIR)/%.c=$(OBJ_DIR)/%.o)

.PHONY: all clean size test check-size help

all: $(TARGET)

$(TARGET): $(OBJS)
	$(CC) $(CFLAGS) -o $@ $(OBJS) $(LDFLAGS)

$(OBJ_DIR)/%.o: $(SRC_DIR)/%.c | $(OBJ_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(OBJ_DIR):
	mkdir -p $(OBJ_DIR)

# 查看最终二进制体积
size: $(TARGET)
	ls -lh $(TARGET)
	@echo "----------------------------"
	@echo "目标：小于 800KB"

# 运行单元测试（CI 使用）
test: $(TARGET)
	$(CC) -std=c99 -O2 -Isrc -o $(OBJ_DIR)/test_engine \
		tests/test_engine.c \
		$(SRC_DIR)/wubi_engine.c \
		$(SRC_DIR)/lru_cache.c \
		$(SRC_DIR)/json_parser.c
	./$(OBJ_DIR)/test_engine

# 强制校验包体 < 800KB（CI 硬性门禁，超限返回非0）
check-size: $(TARGET)
	@size=$$(stat -c %s $(TARGET) 2>/dev/null || stat -f %z $(TARGET)); \
	echo "二进制体积: $$size 字节"; \
	if [ "$$size" -ge 819200 ]; then \
		echo "❌ 超过 800KB 上限，构建失败！"; \
		exit 1; \
	else \
		echo "✅ 体积达标（< 800KB）"; \
	fi

clean:
	rm -rf $(OBJ_DIR) $(TARGET) $(TARGET)_offline
