# 托管与部署（Cloudflare Pages）

> 正式站点：<https://hongshu.igng.net/>。站点源码在产品仓库 `hongshu/website/`，部署由
> `.github/workflows/website.yml` 完成。旧的独立仓库 `IGNGserver/hongshu-website` 已归档。

## 当前配置

| 项 | 值 |
| --- | --- |
| Pages 项目 | `hongshu-website`（Direct Upload，2026-10-25 创建） |
| 部署来源 | `IGNGserver/hongshu` 的 `website/` |
| 部署工作流 | `.github/workflows/website.yml` |
| 生产分支 | `main` |
| 构建命令 | 无（零构建静态站点） |
| 发布目录 | `website/site/` |
| 自定义域名 | `https://hongshu.igng.net/`（zone `igng.net`，2026-10-25 绑定） |
| 默认别名 | `https://hongshu-website.pages.dev/`（保留） |
| DNS | proxied CNAME `hongshu.igng.net` → `hongshu-website.pages.dev` |

站点是首版最小页面，没有 npm 依赖或构建步骤；`website/scripts/check-site.py` 在部署前检查
元信息、本地引用、锚点、外链与体积预算。`website/package.json` 只用于固定部署工具的包边界。

## 自动部署

`website.yml` 在这些时机运行：

- `push` 到 `main` 且改动涉及 `website/**`：检查并部署；
- `Release` 工作流创建完 GitHub Release 后发送 `repository_dispatch`（`release-published`）：
  重新部署（页面目前只链接 Releases 页面，未来加入版本信息时可重新烘焙）；
- `pull_request`：只跑静态检查，不部署；
- `workflow_dispatch`：手动检查与部署。

`release: published` 不能用于这条链路：Release 由 `GITHUB_TOKEN` 创建时，GitHub 不会为它触发新的工作流；
`repository_dispatch` 是例外。

需要两个 repository secrets：

| Secret | 用途 |
| --- | --- |
| `CLOUDFLARE_API_TOKEN` | 最小权限 token，仅需 Cloudflare Pages:Edit |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare 账号 ID |

凭据只放 GitHub Actions Secrets，不写进仓库、文档、日志或前端产物。继续使用现有 Direct Upload 项目，
不要新建项目或改成 Git 集成。

## 发布与回滚

- 正常发布：合并到 `main` 或发布新 Release，工作流自动部署；
- 回滚：Cloudflare Pages 项目 → Deployments 选择上一个成功部署恢复；
- 首次部署完成后在部署记录里补记 deployment ID，并以它作为回滚点；
- 域名变更：修改 DNS 前记录原值；当前是 proxied CNAME `hongshu.igng.net` → `hongshu-website.pages.dev`，
  改动必须同步 Pages 自定义域名。

## 搜索收录与发现

- `website/site/robots.txt` 指向 `https://hongshu.igng.net/sitemap.xml`；`sitemap.xml` 目前只有首页，
  新增页面时同步更新。
- 新域名尚未在 Bing Webmaster Tools / Google Search Console 单独验证；需要时按现有 `*.igng.net`
  站点的做法补验证记录。

## 历史

- 2026-10-25：创建 Cloudflare Pages 项目 `hongshu-website`，绑定 `hongshu.igng.net`；站点代码并入产品仓库
  `website/`，旧的 `hongshu-website` 仓库归档。
