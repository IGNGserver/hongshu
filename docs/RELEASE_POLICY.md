# 发布策略

## 默认不发布

开发阶段允许 CI 自动执行检查和构建，但推送分支、合并 `main` 都不自动创建 GitHub Release。发布使用 `.github/workflows/release.yml`：响应版本 tag（包括 alpha/beta/rc 预发布以及正式版 `vMAJOR.MINOR.PATCH`），校验 tag 与 VERSION 和 main 一致，构建/测试并附上 Android 客户端正式签名安装包与发布说明，并同时向 GHCR 发布中枢容器镜像。

只有用户明确提出“发布预览版/测试版”或“发布正式版”时，才执行对应的发布流程；发布动作必须包含版本校验、目标平台构建、测试、资产校验、中文更新说明和回滚说明。

## 版本与 Release 类型

以下版本创建 GitHub **Pre-release**：

- `vMAJOR.MINOR.PATCH-alpha.N`；
- `vMAJOR.MINOR.PATCH-beta.N`；
- `vMAJOR.MINOR.PATCH-rc.N`。

不带预发布后缀的 `vMAJOR.MINOR.PATCH` 创建正式 **GitHub Release**（stable），并向 GHCR 打 `latest` 标签。除非用户明确要求正式版，否则不能把任何版本标为 stable。

## 发布资产与交付形式

1. **Release 资产严格为三项**：正式签名的 Android APK（`hongshu-<version>-android.apk`）、Linux amd64 中枢工具包（`hongshu-<version>-server-tools-linux-amd64.tar.gz`）和部署文件包（`hongshu-<version>-deployment.tar.gz`）。不上传 `IMAGE.json`、`SHA256SUMS`、`RELEASE_NOTES.md` 或其他辅助文件。
2. **发布说明与校验**：Release 正文沿用 `docs/releases/<tag>.md` 的中文说明，至少包含 `本次更新` 和 `快捷下载`，并提供 APK 直接下载链接。镜像 digest、源码 SHA 与三项资产哈希写入正文末尾的隐藏恢复日志，不作为公开资产；恢复时下载三项资产并重新校验。
3. **中枢服务镜像**：中枢 Docker 容器镜像自动构建并推送到 GitHub Container Registry（`ghcr.io/<owner>/hongshu`），按版本号打标（例如 `ghcr.io/igngserver/hongshu:0.1.0-alpha.5`，正式版额外提供 `latest` 标签）。用户的部署流程不依赖本地源代码构建，而是直接拉取带版本号或 `latest` 标签的镜像。

任何签名密钥、数据库密码、推送密钥或实际短信数据只能通过受控 Secret/环境提供，不能进入发布说明或构建日志。

## 部分发布与不可变性

所有资产/说明与 APK 签名先预检，再推送唯一 staging 标签；记录镜像 digest、源码 SHA 和资产哈希至 Release 正文的隐藏恢复日志。
完整资产集（两个归档和 APK）先上传草稿 Release，再按 digest 提升两个版本标签，
最后公开 Release；仅 stable 随后更新 latest。不要根据重新构建的同名镜像覆盖版本标签。

草稿完整但提升中断：重跑工作流下载原草稿、校验三项资产哈希和源码身份，复用原 digest；已存在同 digest 标签跳过，
不同 digest 或缺失校验资产立即阻止。草稿上传一半、仅有 staging 无草稿、或旧流程留下无草稿版本标签时，
需人工核对并补齐，不自动删除/覆盖任何资产。已公开 Release 不接受重跑；若最后 latest 更新失败，
由维护者核对隐藏恢复日志中的镜像 digest 后显式授权将 latest 指向该 digest，不重新发布版本。
GHCR 与 GitHub Release 无跨服务原子事务，提升期间版本镜像可能先可见；但对应的完整已验证资产已在草稿中保留。
以上测试只模拟故障；本次不实际发布，不改既有 tag/Release。

## 手动恢复入口

发布工作流在构建和签名校验完成后，才会创建 GHCR staging 镜像与 GitHub draft Release。若发布在这一步之后中断，可从 Actions 手动运行 `Hongshu release`，输入同一个既有版本 tag 恢复；流程会校验 draft、校验和、提交来源及镜像 digest，不会覆盖已发布 Release 或已有版本 tag。

同一个 tag 一旦成为已发布 Release，或其版本镜像 tag 已指向不同 digest，恢复流程会停止。不得删除、覆盖或强制重建已发布 tag；应按版本规则准备下一个预发布版本。
