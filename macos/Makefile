# Makefile - CloudWubi macOS 输入法构建
# 目标：编译查询引擎 + IMK 控制器 + 打包 .app 结构
#
# 用法：
#   make build-app    # 编译并打包 CloudWubi.app
#   make test         # 运行核心查询引擎单元测试
#   make check        # 编译 + 测试 + 打包全流程（CI 用）
#   make clean

APP_NAME   = CloudWubi
APP_BUNDLE = $(APP_NAME).app
BUILD_DIR  = build-macos

SRC_IMK   = macos/main.m macos/CloudWubiController.m macos/CWQueryEngine.m
SRC_CORE  = src/wubi_engine.c src/lru_cache.c src/http_client.c src/json_parser.c
HDR_IMK   = -Imacos -Isrc

FRAMEWORKS = -framework Cocoa -framework InputMethodKit -framework Carbon

# 可执行文件目录结构：
#   CloudWubi.app/Contents/MacOS/CloudWubi
#   CloudWubi.app/Contents/Info.plist
#   CloudWubi.app/Contents/Resources/

.PHONY: all build-app test check clean

all: build-app

build-app: $(BUILD_DIR)
	@echo "== 编译输入法可执行文件 =="
	clang -std=c99 -ObjC -fobjc-arc -Os $(HDR_IMK) -o $(BUILD_DIR)/$(APP_NAME) \
		$(SRC_IMK) $(SRC_CORE) $(FRAMEWORKS)
	@echo "== 组装 .app 结构 =="
	rm -rf $(APP_BUNDLE)
	mkdir -p $(APP_BUNDLE)/Contents/MacOS
	mkdir -p $(APP_BUNDLE)/Contents/Resources
	cp $(BUILD_DIR)/$(APP_NAME) $(APP_BUNDLE)/Contents/MacOS/
	cp macos/Info.plist $(APP_BUNDLE)/Contents/Info.plist
	plutil -lint $(APP_BUNDLE)/Contents/Info.plist
	@echo "✅ $(APP_BUNDLE) 打包完成"
	@du -sh $(APP_BUNDLE)

# 核心查询引擎单元测试（不依赖 UI/网关，可离线运行）
test: $(BUILD_DIR)
	@echo "== 编译核心查询引擎测试 =="
	clang -std=c99 -ObjC -fobjc-arc -O2 $(HDR_IMK) -o $(BUILD_DIR)/test_qe \
		macos/test_query_engine.m macos/CWQueryEngine.m \
		src/http_client.c src/json_parser.c src/lru_cache.c src/wubi_engine.c \
		-framework Foundation
	@echo "== 运行测试 =="
	./$(BUILD_DIR)/test_qe

check: test build-app
	@echo "✅ 全部通过"

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

clean:
	rm -rf $(BUILD_DIR) $(APP_BUNDLE)
