# 发布策略

## 默认不发布

开发阶段允许 CI 自动执行检查和构建，但推送分支、合并 `main` 都不自动创建 GitHub Release。当前仓库没有正式发布工作流，也不创建正式 Release。

只有用户明确提出“发布预览版/测试版”或“发布正式版”时，才执行对应的发布流程；发布动作必须包含版本校验、目标平台构建、测试、资产校验、中文更新说明和回滚说明。

## GitHub Pre-release

以下版本必须创建 GitHub **Pre-release**：

- `vMAJOR.MINOR.PATCH-alpha.N`；
- `vMAJOR.MINOR.PATCH-beta.N`；
- `vMAJOR.MINOR.PATCH-rc.N`。

不带预发布后缀的 `vMAJOR.MINOR.PATCH` 才能作为 stable Release。除非用户明确要求正式版，否则不能把任何版本标为 stable，也不能把预发布资产当成生产安装源。

## 未来发布步骤

1. 在任务分支更新 `VERSION`、组件 manifest、迁移说明和 `docs/releases/<tag>.md`；
2. 运行完整验证并推送分支；
3. 经用户决定后创建 PR、合并到 `main`，再创建与版本完全一致的 `v` tag；
4. 由受控 GitHub Actions 构建并校验 Android、Web/PWA、中枢和容器资产；
5. 根据版本后缀创建 Pre-release 或 stable Release，记录校验和、已知问题、部署/回滚方式。

任何签名密钥、数据库密码、推送密钥或实际短信数据只能通过受控 Secret/环境提供，不能进入发布说明或构建日志。
