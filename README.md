# CloudWubi Client — 端侧极简内核

> 面向 5G/6G 的云原生五笔输入法 · 端侧组件
> **安装包 < 800KB** · 纯 C99 · 无第三方依赖 · 跨平台

## 简介

CloudWubi Client 是 CloudWubi 云五笔输入法的**端侧极简内核**。
采用"端轻脑在云"架构：端侧只负责按键采集、编码校验、本地缓存与结果渲染，
**不内置汉字库、词组库、AI模型**，汉字字形复用操作系统原生 CJK 字库，
核心算力与词库全部部署在云端。

| 指标 | 数值 |
| ---- | ---- |
| 二进制体积 | **~15KB**（远小于 800KB 约束） |
| 语言 | 标准 C99 |
| 依赖 | 零第三方库（仅 POSIX socket） |
| 平台 | Windows / macOS / Linux / Android / 鸿蒙（可移植） |

## 架构

```
用户按键 (a~y)
    ↓
cloudwubi-client（本仓库）
  按键捕获 → 编码合法性校验 → 本地LRU缓存 → HTTPS加密上传编码
    ↓
云端网关 (cloudwubi-gateway)
  编码查询 → 动态构词 → 语义排序
    ↓
返回 Unicode 码点
    ↓
平台薄层调用系统 CJK 字库渲染汉字
```

## 模块

| 模块 | 文件 | 职责 |
| ---- | ---- | ---- |
| 五笔引擎 | `src/wubi_engine.c/h` | 编码合法性校验、本地兜底一级简码 |
| LRU缓存 | `src/lru_cache.c/h` | 用户最近选用字词缓存，减少云端请求 |
| HTTP客户端 | `src/http_client.c/h` | 极简 HTTP POST（可编译剔除） |
| JSON解析 | `src/json_parser.c/h` | 极简解析候选码点数组 |
| 主程序 | `src/main.c` | 命令行演示（验证端到端链路） |
| 单元测试 | `tests/test_engine.c` | 19 项核心逻辑测试 |
| macOS 输入法 | `macos/` | InputMethodKit 封装（阶段6） |

## 快速开始

```bash
# 编译（-Os 体积优化，自动校验 <800KB）
make

# 运行单元测试（19项）
make test

# 体积门禁（硬性约束 <800KB，超限返回非0）
make check-size

# 查看体积
make size
```

## 端到端测试

```bash
$ echo "w" | ./cloudwubi_demo
> 候选（本地兜底）: 人

$ echo "wq" | ./cloudwubi_demo   # 需要云端网关已部署
> 候选（云端）: 你
```

## 对接云端

修改 `src/main.c` 中的网关地址：

```c
#define GATEWAY_HOST "你的云函数域名"
#define GATEWAY_PORT 443
#define GATEWAY_PATH "/release/wubi/query"
```

## CI 自动化（GitHub Actions）

| 任务 | 说明 |
| ---- | ---- |
| 编译 | `-Os` 体积优化 |
| 体积门禁 | 强制 < 800KB，超限构建失败 |
| 单元测试 | 19 项核心逻辑测试 |
| 制品上传 | 每次构建产出 `cloudwubi-client-bin` |
| Release | 打 tag（v*）自动打包发布 |

## macOS 输入法（阶段6）

`macos/` 目录包含 InputMethodKit 输入法封装（查询引擎 + IMK 控制器 + .app 打包）：

```bash
cd macos
make test        # 查询引擎单元测试（13项，离线可跑）
make build-app   # 编译并打包 CloudWubi.app
```

| 组件 | 状态 |
| ---- | ---- |
| C 内核 macOS 编译 | ✅ CI 实测（4,512 字节） |
| 查询引擎（CWQueryEngine） | ✅ 13 项单测通过 |
| IMK 控制器 + .app 打包 | ✅ CI 实测（可执行 60,280 字节，.app 64KB） |
| 真机输入体验（弹出/上屏） | ⚠️ 待 macOS 实机验证 |

详见 [macos/README.md](macos/README.md)

## 设计原则

1. **科学性**：编码校验、构词逻辑全部有单元测试覆盖
2. **高效**：单次请求仅数十字节，LRU 缓存减少云端请求
3. **可扩展**：模块解耦，新平台只需替换 `main.c` + 平台薄层
4. **可持续**：零依赖、零维护成本，云端升级无需更新端侧

## 许可

MIT License · 贡献规范见 [CONTRIBUTING.md](CONTRIBUTING.md)

## 相关仓库

- [cloudwubi-gateway](https://github.com/zsdili/cloudwubi-gateway) - 云端网关
- [cloudwubi-rules](https://github.com/zsdili/cloudwubi-rules) - 五笔规则库（去中心化共建）
- [cloudwubi-ai](https://github.com/zsdili/cloudwubi-ai) - AI 引擎（规划中）


## 平台状态

macOS/iOS 官方分发已暂停（Apple 年费门槛声明），Android 已发布。
详见 [docs/PLATFORM-STATUS.md](docs/PLATFORM-STATUS.md)。
