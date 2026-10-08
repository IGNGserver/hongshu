# 版本规则

## 版本源

根目录 `VERSION` 是初始化阶段的唯一版本源。未来 Android、Web、Go 和容器 manifest 如果需要版本字段，必须由该文件同步生成或验证，不能各自漂移。

版本使用 SemVer 2.0.0 的以下形式：

```text
MAJOR.MINOR.PATCH
MAJOR.MINOR.PATCH-alpha.N
MAJOR.MINOR.PATCH-beta.N
MAJOR.MINOR.PATCH-rc.N
```

`N` 从 1 开始递增。初始化版本为 `0.1.0-alpha.1`，表示 API、数据模型和界面都不稳定；它不是 Git tag，也不是 GitHub Release。

## 阶段含义

- **alpha**：核心范围仍在探索，可能有破坏性变更，不建议作为日常数据服务；
- **beta**：主要功能和数据迁移路径已具备，接受试用反馈，但仍可能有兼容性修复；
- **rc**：候选正式版，只接受发布阻塞问题修复，需完成目标平台验收和升级/回滚演练；
- **stable**：不带预发布后缀的版本，例如 `1.0.0`，代表维护者明确批准的正式版本。

主版本为 `0` 时，任何公共 API、数据库 schema 或同步协议都不能假定长期兼容；进入 `1.0.0` 前必须形成迁移和兼容性说明。

## 递增规则

- 破坏公共 API、同步协议或数据库兼容性的变更：递增 MAJOR（在 `0.x` 阶段记录为重大 minor 变化）；
- 新增向后兼容功能：递增 MINOR；
- 向后兼容的修复和文档/构建修订：递增 PATCH；
- 同一预发布系列只递增 `N`，不得重用已经发布的版本号。

## 校验

每次版本准备必须检查 `VERSION`、组件 manifest、镜像 tag、Android `versionCode`/`versionName`（如已存在）的一致性。版本验证命令应登记在根目录 `AGENTS.md` 和 CI 中。
