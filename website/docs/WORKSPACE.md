# 工作区、分支与集成

官网源码位于产品仓库的 `website/`，不再有独立的站点仓库。Git 纪律、worktree 路径与生命周期
以产品仓库根目录 `AGENTS.md` 和设备级规范为准；本文只记录站点相关的差异。

## 目录布局

```text
~/项目/短信同步系统/                   # 产品仓库锚点，不用于开发
~/项目/.wt/短信同步系统/<task-slug>/   # 任务 worktree，唯一开发位置
  └─ website/                          # 官网源码
      ├─ site/                         # 零构建静态页面
      ├─ scripts/check-site.py         # 静态检查
      └─ docs/                         # 站点文档
```

所有改动都从 `origin/main` 建 worktree，不在主目录改文件或建提交。

## 创建任务 worktree

```bash
REPO=~/项目/短信同步系统
git -C "$REPO" fetch origin --prune
BASE=$(git -C "$REPO" symbolic-ref --quiet --short refs/remotes/origin/HEAD | sed 's|^origin/||')
test "$BASE" = main
PATH="$HOME/.local/bin:$PATH" wt new 短信同步系统 hongshu-site
```

`wt` 不可用时的等价命令：

```bash
git -C "$REPO" worktree add ~/项目/.wt/短信同步系统/hongshu-site -b agent/hongshu-site origin/main
```

使用等价命令时也必须确认 worktree 的 `HEAD` 与创建时的 `origin/main` 一致。

## 开发与检查

站点没有构建步骤，也没有 npm 依赖：

```bash
cd website
python3 scripts/check-site.py
python3 -m http.server 8765 --directory site
```

本地预览只看 `site/`；版本、下载与 SEO 的最终形态在 `docs/SITE.md` 的待决策项里，后续修改时
同步更新 `robots.txt` / `sitemap.xml` 与静态检查脚本。

## 收尾与集成

```bash
cd ~/项目/.wt/短信同步系统/<task-slug>
git status --short
git diff --check
python3 website/scripts/check-site.py
git push -u origin agent/<task-slug>
PATH="$HOME/.local/bin:$PATH" wt done 短信同步系统 <task-slug>
```

`main` 受保护，必须经用户授权的 PR，并满足 `verify` 检查；合并后 `.github/workflows/website.yml`
自动部署到 Cloudflare Pages。回滚细节见 [`HOSTING.md`](HOSTING.md)。
