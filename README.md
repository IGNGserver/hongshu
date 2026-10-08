# 鸿枢 · Hongshu

自部署的跨设备短信同步平台，计划由 Android 客户端、Web/PWA 客户端、Go 中枢服务和 MySQL 数据库组成。

> **当前状态：仓库初始化阶段。** 本仓库目前只有目录骨架、开发规范和仓库级 CI，不包含业务实现，也没有可下载版本。

## 计划中的组件

| 目录 | 组件 | 当前状态 |
| --- | --- | --- |
| `android/` | Android 客户端 | 仅目录占位 |
| `web/` | Web/PWA 客户端 | 仅目录占位 |
| `server/` | Go 中枢服务 | 仅目录占位 |
| `db/migrations/` | MySQL 迁移 | 仅目录占位 |
| `docs/` | 架构与维护文档 | 已建立基础规范 |

业务边界、同步协议、认证模型、数据保留策略和部署形态将在后续任务中逐项确定，不在初始化阶段臆造。

## 开发

鸿枢使用远端 `main` 作为唯一基线，主目录只保留 Git 锚点；每项工作使用独立 worktree：

```bash
# 设备级项目根默认为 ~/项目；本机实际路径为 /home/lvziw/项目
cd /home/lvziw/项目/短信同步系统
git fetch origin --prune
git remote set-head origin --auto
PATH="$HOME/.local/bin:$PATH" wt new 短信同步系统 <task-slug>
# 只在 /home/lvziw/项目/.wt/短信同步系统/<task-slug>/ 中开发
```

详细流程见：

- [项目 Agent 规范](AGENTS.md)
- [工作区与分支流程](docs/WORKSPACE.md)
- [架构起点](docs/ARCHITECTURE.md)
- [版本规则](docs/VERSIONING.md)
- [发布边界](docs/RELEASE_POLICY.md)
- [贡献指南](CONTRIBUTING.md)

## 验证

初始化阶段执行：

```bash
bash scripts/verify-repository.sh
git diff --check
```

CI 目前只做仓库结构、版本格式和 Git hygiene 检查；加入具体组件后，再将真实的 Go、Web 和 Android 验证命令接入 CI。

## 安全

不要提交 `.env`、数据库凭据、签名材料或真实短信内容。漏洞和敏感问题不要公开发布可复现细节，按 [SECURITY.md](SECURITY.md) 联系维护者。

## 许可

本项目暂按 MIT License 初始化，详见 [LICENSE](LICENSE)。
