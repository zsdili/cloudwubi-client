# Makefile - CloudWubi macOS 输入法构建
# 目标：编译查询引擎 + IMK 控制器 + 打包 .app 结构
#
# 注意：本 Makefile 设计为在 macos/ 目录内运行（make -C macos 或 cd macos）。
# 源码路径：
#   macos/ 下的文件用相对路径直接引用
#   C 内核在上级 ../src/
#
# 关键设计（解决真实用户问题）：
#   1. Universal 二进制：-arch arm64 -arch x86_64 同时编译
#      （Intel Mac 与 Apple 芯片 Mac 都能运行，修复"禁止符号"问题）
#   2. ad-hoc 签名：codesign -s - 满足输入法签名要求

APP_NAME   = CloudWubi
APP_BUNDLE = $(APP_NAME).app
BUILD_DIR  = build-macos

SRC_IMK   = main.m CloudWubiController.m CWQueryEngine.m
SRC_CORE  = ../src/wubi_engine.c ../src/lru_cache.c ../src/http_client.c ../src/json_parser.c
HDR_IMK   = -I. -I../src

FRAMEWORKS = -framework Cocoa -framework InputMethodKit -framework Carbon

# Universal 双架构（arm64 + x86_64）
ARCH_FLAGS = -arch arm64 -arch x86_64

# 可执行文件目录结构：
#   CloudWubi.app/Contents/MacOS/CloudWubi
#   CloudWubi.app/Contents/Info.plist
#   CloudWubi.app/Contents/Resources/

.PHONY: all build-app test check clean

all: build-app

build-app: $(BUILD_DIR)
	@echo "== 编译输入法可执行文件（Universal 双架构）=="
	clang -std=c99 -ObjC -fobjc-arc -Os $(ARCH_FLAGS) $(HDR_IMK) -o $(BUILD_DIR)/$(APP_NAME) \
		$(SRC_IMK) $(SRC_CORE) $(FRAMEWORKS)
	@echo "== 验证双架构 =="
	lipo -info $(BUILD_DIR)/$(APP_NAME)
	@echo "== 组装 .app 结构 =="
	rm -rf $(APP_BUNDLE)
	mkdir -p $(APP_BUNDLE)/Contents/MacOS
	mkdir -p $(APP_BUNDLE)/Contents/Resources
	cp $(BUILD_DIR)/$(APP_NAME) $(APP_BUNDLE)/Contents/MacOS/
	cp Info.plist $(APP_BUNDLE)/Contents/Info.plist
	plutil -lint $(APP_BUNDLE)/Contents/Info.plist
	@echo "== ad-hoc 签名（满足输入法加载要求）=="
	codesign --force --deep --sign - $(APP_BUNDLE)
	codesign -dv $(APP_BUNDLE) 2>&1 | head -3
	@echo "✅ $(APP_BUNDLE) 打包完成（Universal + 已签名）"
	@du -sh $(APP_BUNDLE)

# 核心查询引擎单元测试（不依赖 UI/网关，可离线运行）
test: $(BUILD_DIR)
	@echo "== 编译核心查询引擎测试 =="
	clang -std=c99 -ObjC -fobjc-arc -O2 $(HDR_IMK) -o $(BUILD_DIR)/test_qe \
		test_query_engine.m CWQueryEngine.m \
		$(SRC_CORE) \
		-framework Foundation
	@echo "== 运行测试 =="
	./$(BUILD_DIR)/test_qe

check: test build-app
	@echo "✅ 全部通过"

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

clean:
	rm -rf $(BUILD_DIR) $(APP_BUNDLE)
