# CloudWubi 纯云端开发操作手册

> 目标：**全程浏览器网页操作**，在线编码、提交代码、触发 CI 自动编译打包，
> 本地不安装开发环境，最终只需下载编译好的程序文件。
>
> 适用仓库：`cloudwubi-client` / `cloudwubi-gateway` / `cloudwubi-rules` / `cloudwubi-ai`
> 平台：GitHub（主开发） + Gitee（国内镜像，自动同步）

---

## 0. 仓库地址速查

| 仓库 | GitHub | Gitee（自动镜像） |
| ---- | ---- | ---- |
| 端侧内核 | github.com/zsdili/cloudwubi-client | gitee.com/zsdili/cloudwubi-client |
| 云端网关 | github.com/zsdili/cloudwubi-gateway | gitee.com/zsdili/cloudwubi-gateway |
| 规则库 | github.com/zsdili/cloudwubi-rules | gitee.com/zsdili/cloudwubi-rules |
| AI引擎 | github.com/zsdili/cloudwubi-ai | gitee.com/zsdili/cloudwubi-ai |

> 💡 开发统一在 **GitHub** 进行（CI、PR、Release 都在这里），代码会自动同步到 Gitee，Gitee 无需手动操作。

---

## 1. 基本概念（小白必读）

| 概念 | 通俗解释 |
| ---- | ---- |
| 仓库 (Repository) | 存放项目代码的文件夹（云端） |
| 分支 (Branch) | 代码的平行版本。`main` 是正式版，永远保持稳定 |
| 提交 (Commit) | 保存一次修改，记录"改了什么" |
| 推送 (Push) | 把本地的提交上传到云端仓库 |
| PR (Pull Request) | 申请把某个分支的修改合并进 `main`，可评审、可回退 |
| CI (持续集成) | 云端自动执行"编译+测试"的流水线，代码一提交就自动跑 |
| Artifact | CI 编译后生成的成品文件（如编译好的客户端程序） |
| Release | 正式发布版本，带版本号（如 v0.1.0），可下载安装包 |

---

## 2. 标准工作流程（网页版，无需本地）

```
1. 新建分支  →  2. 网页编辑代码  →  3. 提交修改  →  4. 创建PR  →  5. CI自动编译验证  →  6. 合并到main  →  7. 下载成品
```

---

## 3. 网页在线编码（详细步骤）

### 步骤1：进入仓库并新建分支
1. 浏览器打开 GitHub 仓库页，例如：https://github.com/zsdili/cloudwubi-client
2. 找到页面左上角的分支下拉框（默认显示 `main`），点击它
3. 在输入框输入新分支名，例如：
   - 修改代码：`feat/update-engine`
   - 修bug：`fix/code-check-bug`
   - 更新文档：`docs/update-readme`
   - 更新规则：`rules/add-wubi-words`
4. 点击 **Create branch: xxx**（创建分支），页面会自动切换到新分支

### 步骤2：网页编辑文件
1. 在新分支页面，浏览文件列表（如 `src/` 目录）
2. 点击进入要修改的文件，如 `src/wubi_engine.c`
3. 点击文件右上角的**铅笔图标 ✏️**（编辑此文件），进入网页编辑器
4. 在编辑器里直接修改代码（支持语法高亮）
5. 需要新增文件时：点击 **Add file → Create new file**，输入文件名和内容

### 步骤3：提交修改
1. 编辑完成后，滚动到页面底部
2. 填写提交信息（Commit message），格式要求见第5节
3. 选择 **Create a new branch for this commit and start a pull request**（推荐）
4. 点击 **Commit changes** 提交

### 步骤4：创建 PR（Pull Request）
1. 提交后会自动进入 PR 创建页面
2. 确认：
   - `base: main`（合并到正式版）
   - `compare: 你的分支`（你的修改）
3. 填写 PR 标题和说明（说明改了什么、为什么）
4. 点击 **Create pull request**

### 步骤5：等待 CI 自动验证
1. PR 页面会显示 CI 任务状态：
   - ✅ 绿色打勾 = 编译和测试全部通过
   - ❌ 红色叉 = 有错误，点击 **Details** 查看日志
2. 若失败：回到分支继续编辑修复 → 提交 → CI 自动重新执行

### 步骤6：合并 PR
1. CI 全部通过后，点击 **Merge pull request** 合并
2. 合并后自动同步到 Gitee（无需手动操作）
3. 建议合并后删除分支（点击 **Delete branch**）

---

## 4. 获取编译成品

### 临时制品（Artifacts，构建后30天内有效）
1. 打开 PR 页面或 Actions 页面
2. 找到成功的构建任务
3. 点击 **Artifacts** 下拉 → 下载 `cloudwubi-client-bin`（编译好的程序包）

### 正式发布（Release，永久保存）
1. 在仓库主页点击 **Releases → Create a new release**
2. 填写版本号（如 `v0.1.0`）和说明
3. 点击 **Publish release**
4. CI 会自动构建并把成品打包到 Release 页面，任何人可下载

---

## 5. 提交信息规范（强制要求）

| 前缀 | 含义 | 示例 |
| ---- | ---- | ---- |
| `feat:` | 新增功能 | `feat: 增加词组查询接口` |
| `fix:` | 修复bug | `fix: 修复编码校验越界` |
| `docs:` | 文档修改 | `docs: 更新部署说明` |
| `rules:` | 五笔规则更新 | `rules: 补充生僻字编码` |
| `refactor:` | 代码重构 | `refactor: 优化LRU缓存结构` |
| `test:` | 测试代码 | `test: 新增编码校验单测` |
| `ci:` | CI/构建配置 | `ci: 添加体积校验` |
| `chore:` | 其他杂项 | `chore: 更新依赖` |

---

## 6. 分仓库开发指引

### cloudwubi-client（C端侧内核）
- 代码位置：`src/` 目录（wubi_engine / lru_cache / http_client / json_parser / main）
- CI 自动执行：编译 → **校验包体 < 800KB（超限直接失败）** → 单元测试
- 修改后重点检查：编码校验逻辑、编译是否通过

### cloudwubi-gateway（Python云端网关）
- 代码位置：`index.py`（腾讯云函数入口）
- 修改后重点检查：Python 语法、请求/响应格式

### cloudwubi-rules（五笔规则库）
- 数据位置：`wubi86_basic.txt`
- 格式：每行 `编码 汉字 汉字...`，如 `wq 你 您`
- CI 自动执行：五笔文法双向校验，非法编码无法合并
- **贡献新词规则走这里**：这是去中心化共建的核心入口

### cloudwubi-ai（AI引擎，阶段2/3）
- 当前为占位仓库，后续开发构词引擎、语义排序、联邦训练

---

## 7. 常见问题（FAQ）

**Q1：我不小心提交错了怎么办？**
A：如果是未合并的分支，直接继续修改再提交即可。如果已合并进 main，在 Issues 里提问题，由维护者处理回退。

**Q2：CI 显示失败，日志我看不懂怎么办？**
A：点击失败任务的 Details，把红色错误信息复制到 Issues 或社区讨论区提问，会有人协助。

**Q3：代码会自动同步到 Gitee 吗？**
A：会。GitHub 每次 push 到 main，自动同步 workflow 会执行，把代码推送到 Gitee（已实测成功）。

**Q4：在 Gitee 上能改代码吗？**
A：可以浏览和编辑，但**不推荐**——GitHub 是主仓库，Gitee 的修改不会自动回到 GitHub，容易造成两边不一致。统一在 GitHub 操作。

**Q5：需要安装 Git 吗？**
A：不需要。本手册全部是网页操作，不需要本地安装任何开发工具。

**Q6：CI 免费额度够吗？**
A：够。本项目代码很小，单次构建仅几十秒，GitHub Actions 免费额度（每月2000分钟）绰绰有余。

---

## 8. 安全边界

1. **令牌/密钥绝不提交到仓库**：云端网关密钥、Redis密码等敏感信息只能放 CI 平台的环境变量（secrets）中
2. 令牌已在对话中出现过，建议任务完成后轮换（生成新令牌、吊销旧令牌）
3. 仓库为公开仓库，**不要提交任何个人敏感信息、密码、真实姓名地址**

---

## 9. 当前状态与下一步

| 项目 | 状态 |
| ---- | ---- |
| 4仓库创建 + 双向镜像 | ✅ 完成 |
| 本操作手册 | ✅ 完成 |
| CI编译+体积校验+单元测试 | ⏳ 下一步（A步骤） |
| Release自动打包 | ⏳ 随A步骤配置 |
