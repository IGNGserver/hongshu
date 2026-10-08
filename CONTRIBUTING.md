# 贡献与开发

## 开始任务

1. 阅读根目录 `AGENTS.md` 和相关架构文档。
2. 在仓库锚点执行 `git fetch origin --prune`，确认远端默认分支，再创建独立 worktree。
3. 在任务 worktree 中实现、测试和更新文档；不要在主目录开发。
4. 提交前检查 `git status`、`git diff`、`git diff --check`，确认没有凭据、构建产物或无关改动。
5. 运行 `bash scripts/verify-repository.sh` 及本任务登记的组件验证命令。
6. 提交并推送任务分支。是否创建 PR、合并或发布由维护者明确决定。

## 提交

使用 Conventional Commits：

```text
feat(server): add ...
fix(android): handle ...
docs: record ...
test(web): cover ...
```

提交信息不要包含密码、token、短信正文或未公开的部署信息。按项目 Agent 规范添加 `Assisted-by` trailer，不添加虚假的作者信息。

## 变更边界

接口、数据库迁移、客户端行为和部署文档必须保持同步。新增依赖、外部服务或凭据存储方式时，先在设计文档或任务说明中记录理由和风险。

## 发布

普通开发不会自动发布。只有用户明确要求时，才按照 `docs/RELEASE_POLICY.md` 更新版本、打 tag 和创建 GitHub Release。
