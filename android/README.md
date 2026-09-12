# 云五笔 Android 端

云五笔（CloudWubi）Android 输入法——云端智能五笔，端侧极简（<800KB）。

## 设计原理（先科学后先进）

| 层 | 职责 | 位置 |
| --- | --- | --- |
| 端侧 | 按键采集、编码校验、候选渲染、断网兜底 | `app/src/main/java/com/cloudwubi/ime/` |
| 云端 | 动态构词、语义排序、MRU 学习 | cloudwubi-gateway / cloudwubi-ai |
| 协议 | JSON：`{"code":"wqvb","phrase":true}` → `candidates`+`phrases` | HTTP POST |

- 端侧无词库：汉字来自云端编码库（24KB 级），动态组合无限输入
- 断网降级：本地 25 个一级简码高频字兜底
- 候选规则：1码高频单字 → 2码先单字再二字词 → 上次选中置顶 → 3码预测第4码词组

## 构建（CI 自动）

```bash
# 本地（需 Android SDK）
bash build_apk.sh
```

GitHub Actions 已配置 `android-build.yml`：推 main 自动构建 + 传 tag 自动挂 Release 资产。

## 安装（Android 7.0+，侧载）

1. 下载 Release 中的 `CloudWubi.apk`
2. 手机设置 → 安全 → 允许"安装未知来源应用"
3. 安装后：设置 → 系统 → 语言与输入法 → 键盘 → 管理键盘 → 勾选「云五笔」
4. 切换到云五笔即可输入

## 配置云端网关

编辑 `CloudWubiIME.java` 顶部：
```java
private static final String GATEWAY_URL = "https://YOUR-GATEWAY-URL/release/wubi/query";
```
部署 cloudwubi-gateway（腾讯云函数）后替换该地址。

## 目录

```
android/
├── build_apk.sh          # 命令行构建（无 gradle 依赖）
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/cloudwubi/ime/CloudWubiIME.java
    └── res/               # 图标+输入法声明
```

## 路线图

- [x] IME 服务骨架（按键+候选+上屏）
- [x] 云端查询+断网兜底
- [x] 软键盘视图（数字选字行+五笔26键+删除/回车）
- [x] 首版 APK Release（v0.4.0，16.5KB）
- [ ] 真实网关地址接入
- [ ] 候选翻页（当前显示前10个）
- [ ] 联想/热词缓存
