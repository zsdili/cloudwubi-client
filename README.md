# CloudWubi Client — 云五笔输入法（Android 主线）

> 又快又准还智能的云原生五笔输入法 · 开源共建
> **安装包 ≤ 100KB（当前 53.3KB）** · 五笔86 规则 · 端侧离线词库兜底 + 云端扩展
> 双仓开源：GitHub + Gitee 同步

## 简介

CloudWubi 云五笔，面向 5G/6G 的轻量五笔输入法。**端侧只带精简词库（离线可打字），云端（腾讯云 SCF 函数）动态扩展词库与联想**。遵循传统五笔86 编码规则：一级/二级简码 → 单字 → 词组 → 光标前字联想 → 最近上屏（MRU）置顶 → 云端热点，越用越聪明。

| 指标 | 数值 |
| ---- | ---- |
| APK 体积 | **53.3KB**（硬性约束 ≤ 100KB） |
| 词库 | 端侧精简词库（单字 6500+ / 词组 4200+，离线可用） |
| 云端 | 腾讯云 SCF 网关（编码查询 / 联想 / 热点扩展） |
| 规则 | 五笔86（一级/二级简码、词组、光标前字联想、MRU 置顶） |
| 平台 | Android 7+（Android 主线已发布；macOS 已声明放弃，见 docs/PLATFORM-STATUS.md） |

## 下载（最新 v0.5.51，99.9KB）

- GitHub：https://github.com/zsdili/cloudwubi-client/releases/download/v0.5.51/CloudWubi.apk
- Gitee：https://gitee.com/zsdili/cloudwubi-client/releases/download/v0.5.51/CloudWubi.apk
- **固定签名**：使用公开固定签名（android/keystore/cloudwubi.jks），各版本可直接覆盖安装，无需卸载
- v0.5.51 更新：APP 图标"云五笔"印章重绘（字宽 85%）、全选改图标⭕️、云端口头禅词库 37 条 + 6 类词库 81 条（网红/打卡点/景点/线路/客服语/热词）、86 全码规则清理（"不"字简码首码→全码）

## 问题反馈（欢迎共建）

- GitHub Issues：https://github.com/zsdili/cloudwubi-client/issues
- Gitee Issues：https://gitee.com/zsdili/cloudwubi-client/issues
- 作者 zsdili | 微信 175571
- 开源协议：MIT（完全免费，欢迎贡献词库/规则/翻译，见 CONTRIBUTING.md）

## 架构

```
用户按键 (a~y)
    ↓
CloudWubiIME（本仓库 Android 端）
  按键 → 五笔86编码校验 → 端侧词库查询（离线优先，MRU/联想排序）
    ↓ 未命中（可选云端增强）
腾讯云 SCF 网关 (cloudwubi-gateway)
  编码查询 / 前缀联想 / 热点词扩展
    ↓
候选上屏（支持中文/英文/数字/符号/计算/剪贴板）
```

## 模块

| 模块 | 文件 | 职责 |
| ---- | ---- | ---- |
| 输入法主类 | `android/app/src/main/java/com/cloudwubi/ime/CloudWubiIME.java` | 会话/候选/联想/计算/剪贴板/排序 |
| 键盘视图 | `android/app/src/main/java/com/cloudwubi/ime/CloudKeyboardView.java` | 主键盘/数字/符号面板渲染与手势 |
| 词库引擎 | `android/app/src/main/java/com/cloudwubi/ime/WubiDb.java` | 端侧词库加载/编码索引 |
| 词库数据 | `android/app/src/main/assets/wubi_{single,phrase}.txt` | 单字/词组（assets 文本压缩，省体积） |
| 构建脚本 | `android/build_apk.sh` | 命令行打包（aapt2+javac+d8+固定签名） |
| 云端网关 | cloudwubi-gateway 仓库 | 腾讯云 SCF 函数 |

## 快速开始（构建 APK）

```bash
# 环境：Android SDK（ANDROID_HOME）+ JDK
cd android
bash build_apk.sh
# 产物：android/build-apk/CloudWubi.apk（≤100KB 自动校验）
```

## CI 自动化（GitHub Actions）

| 工作流 | 内容 |
| ---- | ---- |
| Android Build | 构建 APK + 体积门禁（≤100KB）+ 固定签名 + 产物 cloudwubi-apk |
| Sync to Gitee | 自动镜像到 Gitee（四仓同名） |
| Build & Test | 仓库级构建测试 |

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
| 构建 | `bash android/build_apk.sh`（aapt2+javac+d8+固定签名） |
| 体积门禁 | 强制 ≤ 100KB，超限构建失败 |
| 签名 | 固定 keystore（android/keystore/cloudwubi.jks），各版本可覆盖安装 |
| 制品上传 | 每次构建产出 `cloudwubi-apk`（含 CloudWubi.apk） |
| Release | 打 tag（v*）双平台发布（GitHub + Gitee） |

## macOS 输入法（已暂停）

macOS/iOS 官方分发已暂停（Apple 开发者年费门槛，与"免费开源"初心相悖），已声明放弃。
详见 [docs/PLATFORM-STATUS.md](docs/PLATFORM-STATUS.md)。

## 设计原则

1. **科学性**：五笔86 编码规则（一级/二级简码 → 单字 → 词组 → 光标前字联想 → MRU 置顶 → 云端热点）
2. **高效**：端侧离线词库毫秒级响应，云端（腾讯云 SCF）微秒级扩展，MRU 越用越聪明
3. **可扩展**：词库/规则/云端/AI 分仓解耦，去中心化共建（cloudwubi-rules 接受全网贡献）
4. **可持续**：安装包 ≤100KB、零依赖、开源免费，贡献值/口碑/自我传播为成功指标

## 许可

MIT License · 贡献规范见 [CONTRIBUTING.md](CONTRIBUTING.md)

## 相关仓库

- [cloudwubi-gateway](https://github.com/zsdili/cloudwubi-gateway) - 云端网关（腾讯云 SCF）
- [cloudwubi-rules](https://github.com/zsdili/cloudwubi-rules) - 五笔规则库（去中心化共建）
- [cloudwubi-ai](https://github.com/zsdili/cloudwubi-ai) - AI 引擎（规划中）
