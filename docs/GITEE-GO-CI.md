# Gitee Go 流水线配置说明（可选，镜像端备用）

> GitHub 是主开发仓库，CI 已在 GitHub Actions 完整配置并验证通过。
> 本文件提供 Gitee Go 等价配置，供需要在国内环境构建时使用。

## 一、启用 Gitee Go

1. 登录 Gitee → 进入仓库 `cloudwubi-client`
2. 左侧菜单找到「流水线 Gitee Go」
3. 首次使用需**开通 Gitee Go 服务**（免费额度）
4. 在仓库根目录创建 `.workflow/cloudwubi-client.yml`（见下）

## 二、cloudwubi-client 流水线配置

创建文件 `.workflow/cloudwubi-client.yml`：

```yaml
version: "1.0"
name: cloudwubi-client-build
stages:
  - name: build
    steps:
      - name: checkout
        uses: gitee/checkout@v1

      - name: install gcc
        run: |
          sudo apt-get update -qq
          sudo apt-get install -y -qq gcc

      - name: compile
        run: make

      - name: check-size (must < 800KB)
        run: make check-size

      - name: unit-test
        run: make test

      - name: upload-artifact
        uses: gitee/upload-artifact@v1
        with:
          path: cloudwubi_demo
          name: cloudwubi-client-bin
```

## 三、cloudwubi-rules 流水线配置

创建文件 `.workflow/rule-verify.yml`：

```yaml
version: "1.0"
name: rule-verify
stages:
  - name: verify
    steps:
      - name: checkout
        uses: gitee/checkout@v1
      - name: grammar-check
        run: python3 rule_verify.py wubi86_basic.txt
```

## 四、触发方式

- 推送代码 / 创建 PR 时自动触发
- 也可以在 Gitee Go 页面手动点击「执行」

## 五、注意事项

1. Gitee Go 与 GitHub Actions 功能等价，二选一即可；主开发统一在 GitHub
2. Gitee Go 免费构建时长有限，本仓库代码极小、构建很快，消耗可忽略
3. 若同时启用两边 CI，同一代码会构建两次，属正常现象
