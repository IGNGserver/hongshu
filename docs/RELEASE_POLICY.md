# 发布策略

## 默认不发布

开发阶段允许 CI 自动执行检查和构建，但推送分支、合并 `main` 都不自动创建 GitHub Release。发布使用 `.github/workflows/release.yml`：响应版本 tag（包括 alpha/beta/rc 预发布以及正式版 `vMAJOR.MINOR.PATCH`），校验 tag 与 VERSION 和 main 一致，构建/测试并附上 Android 客户端正式签名安装包与发布说明，并同时向 GHCR 发布中枢容器镜像。

只有用户明确提出“发布预览版/测试版”或“发布正式版”时，才执行对应的发布流程；发布动作必须包含版本校验、目标平台构建、测试、资产校验、中文更新说明和回滚说明。

## 版本与 Release 类型

以下版本创建 GitHub **Pre-release**：

- `vMAJOR.MINOR.PATCH-alpha.N`；
- `vMAJOR.MINOR.PATCH-beta.N`；
- `vMAJOR.MINOR.PATCH-rc.N`。

不带预发布后缀的 `vMAJOR.MINOR.PATCH` 创建正式 **GitHub Release**（stable），并向 GHCR 打 `latest` 标签。除非用户明确要求正式版，否则不能把任何版本标为 stable。

## 发布资产与交付形式

1. **Android 客户端安装包**：GitHub Release 的发布资产仅包含正式签名的 Android APK（`hongshu-<version>-android.apk`）与对应校验文件。使用项目受保护的独立私有签名证书签名，不发布 debug 版 APK。
2. **中枢服务镜像**：中枢 Docker 容器镜像自动构建并推送到 GitHub Container Registry（`ghcr.io/<owner>/hongshu`），按版本号打标（例如 `ghcr.io/igngserver/hongshu:0.1.0-alpha.5`，正式版额外提供 `latest` 标签）。用户的部署流程不依赖本地源代码构建，而是直接拉取带版本号或 `latest` 标签的镜像。

任何签名密钥、数据库密码、推送密钥或实际短信数据只能通过受控 Secret/环境提供，不能进入发布说明或构建日志。
