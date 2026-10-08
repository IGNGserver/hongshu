# 工作区、分支与集成

## 目录布局

本机项目根为 `/home/lvziw/项目`（其他设备可通过 `WT_ROOT_DIR` 指定）。鸿枢的 Git 锚点目录是：

```text
/home/lvziw/项目/短信同步系统/                 # .git + 本地锚点，不放源码
/home/lvziw/项目/.wt/短信同步系统/<task-slug>/ # 任务 worktree，唯一开发位置
```

仓库采用普通 Git 锚点 + Git Worktree，以兼容本机现有 `wt` 工具和跨设备共享卷；不在主目录长期维护本地 `main` 的检出源码。GitHub 远端 `main` 是唯一开发基线。

## 创建任务 worktree

```bash
REPO=/home/lvziw/项目/短信同步系统
git -C "$REPO" fetch origin --prune
git -C "$REPO" remote set-head origin --auto
BASE=$(git -C "$REPO" symbolic-ref --quiet --short refs/remotes/origin/HEAD | sed 's|^origin/||')
test "$BASE" = main
PATH="$HOME/.local/bin:$PATH" wt new 短信同步系统 sms-api-contract
```

`wt new` 会从 `origin/main` 创建 `agent/sms-api-contract` 和对应目录。任务 slug 使用可读的 ASCII 名称，不使用随机名。若工具不可用，等价命令为：

```bash
git -C "$REPO" worktree add \
  /home/lvziw/项目/.wt/短信同步系统/sms-api-contract \
  -b agent/sms-api-contract origin/main
```

使用等价命令时也必须确认 worktree 的 `HEAD` 与 `origin/main` 创建时一致。

## 开发与同步

- 不使用 `git pull`；需要同步时先 `git fetch origin --prune`。
- 任务进行中不要因为 `origin/main` 前进就擅自 rebase/merge；只有集成前、冲突阻塞或任务确实依赖基线更新时才处理，并重新跑全部验证。
- 不修改其他任务 worktree，不复用 worktree，不在 worktree 内嵌套 worktree。
- 构建产物、依赖缓存和临时数据不要写入提交；优先使用设备规范指定的共享缓存目录。

## 收尾

```bash
git status --short
git diff --check
bash scripts/verify-repository.sh
git push -u origin agent/<task-slug>
```

推送前确认改动只属于当前任务。清理前必须确认没有未提交内容和未推送提交，再使用：

```bash
PATH="$HOME/.local/bin:$PATH" wt done 短信同步系统 <task-slug>
```

`wt done` 遇到未提交或未推送内容会保护性停止。不要用 `rm -rf`、`git clean -fdx` 或强制删除来“整理”现场；归属不明或未完成分支交由维护者判断。

## 集成与发布

`main` 受保护，集成必须通过用户决定的 PR。默认使用 squash merge；本项目不会自动创建 PR、合并、打 tag 或发布 Release。分支保护要求仓库级 CI 通过，但不要求机器人自动批准或合并。
