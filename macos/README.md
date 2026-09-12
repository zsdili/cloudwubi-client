# CloudWubi macOS 输入法（阶段6）

> 云五笔输入法的 macOS 端：复用 C 内核，InputMethodKit 封装为系统输入法

## 状态

| 组件 | 状态 | 说明 |
| ---- | ---- | ---- |
| C 内核 macOS 编译 | ✅ 已验证 | 真实 macOS runner 编译+测试+体积门禁通过（4,512 字节） |
| CWQueryEngine（查询核心） | ✅ 已实现+单测 | 编码校验/云端查询/本地兜底/选词上报 |
| IMK 控制器（UI 薄层） | ✅ 已实现 | 按键处理/候选窗口/上屏 |
| .app 打包 | ✅ 构建脚本 | `make build-app` |
| 真机输入体验 | ⚠️ 待验证 | 需 macOS 实机（无头环境无法完整模拟弹出/上屏） |

## 目录结构

```
macos/
├── main.m                 # IMKServer 入口
├── CloudWubiController.h/m # IMK 输入控制器（UI 薄层）
├── CWQueryEngine.h/m       # 查询核心（纯逻辑，可单测）
├── test_query_engine.m     # 查询核心单元测试
├── Info.plist              # 输入法声明（连接名/输入模式）
└── Makefile                # 构建/测试/打包
```

## 构建与测试

```bash
# 核心查询引擎单元测试（离线可跑）
make test

# 编译 + 打包 CloudWubi.app
make build-app

# 全流程（CI 使用）
make check
```

## 架构

```
用户按键 (a~y)
  → CloudWubiController (IMKInputController)
      → CWQueryEngine (查询核心，已单测)
          → http_client POST 云端网关（构词+AI排序）
          → json_parser 解析候选
          → wubi_engine 本地兜底（断网可用）
  → IMKCandidates 候选窗口
  → 用户选中 → insertText 上屏 → reportSelection 云端学习
```

## 安装（真机）

1. 构建 CloudWubi.app
2. 拷贝到 `~/Library/Input Methods/`
3. 系统设置 → 键盘 → 输入法 → 添加「云五笔」
4. 首次需在隐私设置授权

## 路线

- [x] 6a：内核 macOS 兼容验证（CI 实测）
- [x] 6b：IMK 封装代码 + 构建脚本（本目录）
- [ ] 6c：真机输入体验验证（需 macOS 实机）
- [ ] 6d：签名/公证/发布
