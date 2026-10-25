# 鸿枢官网 · Hongshu Website

鸿枢 / Hongshu 的产品官网与下载入口，源码位于产品仓库的 `website/`：

- 产品仓库（唯一事实来源）：<https://github.com/IGNGserver/hongshu>
- 正式站点：<https://hongshu.igng.net/>（Cloudflare Pages 项目 `hongshu-website`）
- 部署：`.github/workflows/website.yml` 在 `main` 合并后自动发布

这是**首版最小页面**：零构建 HTML/CSS，不含统计脚本、第三方字体或前端框架。产品完整页面、
视觉稿与语言策略仍在 `docs/SITE.md` 的待决策项里，后续可以在这个 `website/` 里替换内容，
不需要再建独立站点仓库。

## 目录结构

| 路径 | 内容 |
| --- | --- |
| `site/` | 静态页面：首页、404、样式、图标、`_headers`、`robots.txt`、`sitemap.xml` |
| `scripts/check-site.py` | 静态检查：元信息、本地引用、外链、锚点、体积预算 |
| `docs/SITE.md` | 官网定位、当前范围与待决策项 |
| `docs/HOSTING.md` | Cloudflare 托管、自动部署与回滚 |
| `docs/WORKSPACE.md` | 本机 worktree 与验证约定 |

## 开发

站点没有构建步骤，也没有 npm 依赖：

```bash
cd website
python3 scripts/check-site.py
python3 -m http.server 8765 --directory site
# http://127.0.0.1:8765/
```

`package.json` 只用于固定 npm 的包边界，避免部署工具向上读到产品仓库；不要在这里添加前端依赖。

## 许可

[MIT](../LICENSE)
