# 鸿枢官网 · Hongshu Website

鸿枢 / Hongshu 的产品官网与下载入口，源码位于产品仓库的 `website/`：

- 产品仓库（唯一事实来源）：<https://github.com/IGNGserver/hongshu>
- 正式站点：<https://hongshu.igng.net/>（Cloudflare Pages 项目 `hongshu-website`）
- 部署：`.github/workflows/website.yml` 在 `main` 合并后自动发布

站点采用**零构建静态架构**（手写语义化 HTML、CSS 与原生 ES Modules / WebGL），不含统计脚本、第三方字体或任何 npm 前端依赖：

- **视觉风格**：暖白瓷面、雾蓝与柔和拉丝金属质感，融合 Android / Web 客户端的 Material 3 Expressive 界面语言，支持浅色 / 深色主题切换；
- **3D 跨设备归集舞台**：原生 WebGL 渲染多台手机、私有中枢与桌面收件箱之间的柔和光线汇聚路径，并随滚动切换镜头焦点（低算力或无 WebGL 环境平滑降级）；
- **双端交互工作台**：使用纯虚构脱敏数据演示实时验证码入站同步、设备与双卡筛选、搜索提取、一键复制 OTP、只读通话记录、本机联系人本地装饰及可选 AI 分组边界说明。

## 目录结构

| 路径 | 内容 |
| --- | --- |
| `site/` | 静态发布目录：`index.html`、`404.html`、`_headers`、`robots.txt`、`sitemap.xml` |
| `site/assets/style.css` | 暖白 / 雾蓝 / 柔和金属设计系统、Material 3 演示组件与响应式样式 |
| `site/assets/stage3d.js` | 零依赖 WebGL2/WebGL1 3D 硬件建模、光路粒子与弹簧镜头引擎 |
| `site/assets/app.js` | 滚动编排、双端交互工作台（FLIP 弹簧动画、筛选、搜索、OTP 复制）与主题切换 |
| `site/assets/data.js` | 100% 虚构脱敏演示数据集、M3 头像规则与版本化部署配置模板 |
| `scripts/check-site.py` | 静态检查：元信息、CSP 约束、模块引用、外链、锚点、隐私脱敏与体积预算 |
| `docs/SITE.md` | 官网定位、设计定案与内容边界原则 |
| `docs/HOSTING.md` | Cloudflare Pages 托管、自动部署与回滚 |
| `docs/WORKSPACE.md` | 本机 worktree 与验证约定 |

## 本地开发与检查

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
