# 鸿枢（Hongshu）项目 Agent 规范

本文件只记录鸿枢项目特有的约定。设备级 Git、worktree、冲突处理和安全规范以
`~/.qoder/coder-rules/global-rules.md` 为准；若本文件与设备级安全条款冲突，以设备级条款为准。

Collaboration: solo
Default branch: main
Baseline: main
Integration: user-authorized PR only
Release: manual-on-explicit-request
Validation: `bash scripts/verify-repository.sh`、`git diff --check`
Worktree: `~/项目/.wt/短信同步系统/<slug>`

## 项目范围

鸿枢是一个自部署的跨设备短信同步平台，计划包含：

- `android/`：Android 客户端；
- `web/`：Web/PWA 客户端；
- `server/`：Go 中枢服务；
- `db/migrations/`：MySQL 数据库迁移；
- `docs/`：架构、接口、安全和运维文档。

当前提交只初始化仓库、工作区和开发规范，不包含业务代码、数据库表、客户端权限申请或可部署产物。
后续实现必须先更新相关架构/接口文档，再同步代码、迁移和测试。

## 开工与工作区

主目录 `/home/lvziw/项目/短信同步系统` 是仓库锚点，不用于长期检出源码或开发。每个任务都必须从远端默认分支创建独立 worktree：

```bash
git -C /home/lvziw/项目/短信同步系统 fetch origin --prune
git -C /home/lvziw/项目/短信同步系统 remote set-head origin --auto
BASE=$(git -C /home/lvziw/项目/短信同步系统 symbolic-ref --quiet --short refs/remotes/origin/HEAD | sed 's|^origin/||')
PATH="$HOME/.local/bin:$PATH" wt new 短信同步系统 <task-slug>
```

如果 `wt` 不在 `PATH`，使用 `$HOME/.local/bin/wt`。生成的目录是
`/home/lvziw/项目/.wt/短信同步系统/<task-slug>/`，只在该目录中修改文件。
任务 slug 只能使用 ASCII 字母、数字、点、下划线和连字符；分支使用 `agent/<slug>`、
`feature/<slug>`、`fix/<slug>` 或 `refactor/<slug>` 等可读前缀。

禁止在主目录 `checkout`、`switch`、修改源码或建立开发提交；禁止使用 `git pull`。最多同时保留两个活跃
worktree。完成任务后，先确认提交已推送且没有未提交改动，再按设备规范使用 `wt done`；未提交内容或未推送分支不得清理。

## 分支、提交与集成

- `main` 是唯一远端开发基线，所有任务从 `origin/main` 开始。
- 任务完成后提交并推送任务分支；不得未经用户明确要求创建 PR、合并 `main`、删除远端分支或发布 Release。
- 提交信息使用 Conventional Commits，例如 `feat(android): ...`、`fix(server): ...`、`docs: ...`。
- 提交说明应解释原因而不只是罗列文件。按设备规范添加 `Assisted-by: <harness>/<model>` trailer；不要添加虚假的 `Co-Authored-By`。
- PR（由用户要求创建时）必须说明变更目的、影响范围、实际验证命令、已知风险和未完成项。
- 数据库结构变更必须带可回滚/可审计的迁移，并说明已有部署的数据迁移路径；不得把生产数据库凭据写入代码、文档、测试夹具或日志。

## 验证

当前没有业务实现，仓库级验证只有：

```bash
bash scripts/verify-repository.sh
git diff --check
```

加入 Go、Web 或 Android 实现后，必须在本文件和 CI 中登记真实存在的格式检查、静态检查、单元测试和构建命令；不得为了让 CI 变绿而删除测试、弱化断言或跳过失败。

## 版本与发布

根目录 `VERSION` 是当前唯一版本源，使用 SemVer：`MAJOR.MINOR.PATCH`，预发布形式为
`MAJOR.MINOR.PATCH-alpha.N`、`-beta.N` 或 `-rc.N`。规则和发布边界见 `docs/VERSIONING.md` 与
`docs/RELEASE_POLICY.md`。

- `alpha`：接口和功能仍在快速变化，不能视为可用版本；
- `beta`：主要功能已具备，允许试用但仍可能有兼容性变更；
- `rc`：候选稳定版，只接受发布阻塞问题修复；
- stable：不带预发布后缀的正式 SemVer，例如 `1.0.0`。

所有带 `-alpha.N`、`-beta.N`、`-rc.N` 的 GitHub Release 都必须标记 **Pre-release**；stable
Release 必须由用户明确要求。日常开发不自动递增版本、不创建 tag、不创建 Release；本轮不创建正式 Release。

## 凭据与敏感数据

凭据只通过环境变量、受控部署 Secret 或本机未跟踪配置提供。`.env`、私钥、签名文件、数据库导出和真实短信内容
不得提交。日志、issue、PR、提交信息和文档不得包含口令、token、API key 或个人短信正文。

## 结构维护

新增公共接口、数据库表、同步协议或客户端权限时，必须同时更新对应文档、迁移、测试和 CI。不要在没有业务需求的情况下提前选择同步冲突策略、数据保留期限或认证方式；这些决策应在实现任务中形成记录。
