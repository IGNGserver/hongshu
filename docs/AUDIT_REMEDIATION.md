# Engineering audit remediation

基线：`e6155be`（2026-10-11 repository-wide audit）。本任务不发布、不部署；保留已有身份、短信与未上传队列。

顺序：P1 核心闭环 → 采集/恢复及交付 → 性能/体验 → 元数据和潜在风险。
F01–F29、R01/R02 已实施修复；下列验收是隔离本地测试，不是生产/真机全功能认证。

| 批次 | 审计项 | 修复契约 |
|---|---|---|
| 1 | F01 | MySQL 事务内选择有限任务并更新租约；执行真实 worker 测试 |
| 1 | F02/F03 | 去重保留本机/SIM/捕获来源，已知未映射订阅不兜底；单卡确认绑定当前订阅 |
| 1 | F04 | 认证存储不可用返回 503，不撤销本地凭据 |
| 1 | F05 | 恢复代次高于恢复前和备份代次，不从备份简单加一 |
| 1 | F06 | HTTPS 身份必须使用 Secure cookie；HTTPS 代理显式使用 HTTPS PUBLIC_URL |
| 2 | F07–F09 | 连接时记录起点；provider 身份账本跨 ACK 保留；回看处理晚插入 |
| 2 | F10/F11 | 初次补齐基线、待通知意图和每页 epoch 与数据原子保存；两端处理 409 |
| 2 | F12/F13 | 密码不规范化；请求绑定会话代次，退出取消旧请求 |
| 2 | F20/F21 | 代理边缘按客户端限速；中枢全局有界密码校验 |
| 2 | F22/F23 | 发布前校验全部资产，镜像按 digest 提升；部署固定已发布版本 |
| 2 | F25/F26 | 撤销测试拒绝超时；采集与网络健康分开，错误脱敏可观测 |
| 3 | F14/F15/F24 | 空更新不重载历史；Web 从 head 观察；壳资源内容版本化 |
| 3 | F16–F19 | 合并触发，有界索引扫描，联系人增量更新，UI 数据库工作移至 IO |
| 4 | F27–F29 | 单调 APK versionCode、刷新设置、移除无消费者别名 |
| 4 | R01/R02 | 排除非公共特殊地址；发送前复核授权/订阅 |

Android 增量迁移保留 outbox/inbox/SIM；捕获账本不对旧历史猜测去重。已有错误归属不自动改写，需要用户审计确认。

消息 JSON 与数字 epoch 向后兼容。日志不含正文/凭据。实际结果追加到本文件和 VALIDATION.md；真机与生产 Push 不由编译代替。

## 修复与回归对应

| 项目 | 实施与回归 |
| --- | --- |
| F01、R02 | 实际 MySQL worker 领取→加密发送→重试→失效端点；发送前复核撤销/通知开关/订阅快照，状态写入带租约 |
| F02/F03/F09 | SQLite 捕获账本隔离本机/SIM/两种采集来源；ACK 不删除账本；模拟器覆盖远端缓存、双 SIM、独立 provider 行、ACK 空窗、未知来源 |
| F04 | 认证数据库故障 503→恢复复用同 token/id；Android HTTP 合成故障保留 Keystore token |
| F05 | 两次恢复同一真实 mysqldump，生成代次 2→3；新 head 超过旧 cursor 仍能区分 epoch |
| F06/F20/F21 | HTTPS Cookie、可信代理与直连伪造头、独立限速桶、全局两个哈希槽回归；修正完整 Nginx 示例 |
| F07/F08 | 登录持久起点；旧客户端缺起点时由 /me.created_at 补齐，绝不取首次扫描时间；provider ID 代替日期排序水位 |
| F10/F11 | 同事务保存初次补齐、通知意图与每页 epoch；模拟器验证部分页面失败及混代拒绝；Web 不再访问会丢弃正文的 /sync |
| F12/F13 | Android 实际 HTTP 登录保留首尾空白；Web API 真正传递 AbortSignal、会话代次拒收旧响应 |
| F14/F15/F28 | 空观察不重取正文，真实更新保留已读范围与滚动位置；观察当前 head；更新未编辑名称，保留编辑并防止覆盖其他设备新值 |
| F16 | 模拟器实际 WorkManager 退避期 100 次触发只保留一项待运行任务；手动重试替换退避项；运行时至多追加一个复查任务 |
| F17/F18/F19 | 批量捕获事务/范围索引/有界 outbox keyset，缓存映射；联系人差异更新与零改动写入测试；会话选项改为 IO 查询 |
| F22/F23 | 无真实发布：mock 验证缺资产、部分提升恢复、拒绝异 digest、不可变公开 Release 与损坏草稿；初始化强制已发布固定版本 |
| F24 | 实际构建内容哈希变化→SW 安装/激活→离线完整新壳；API 不进 cache；Docker 使用相同构建输出 |
| F25/F26 | WS 未撤销正对照与服务器主动关闭（超时不通过）；采集失败和同步健康独立，模拟器验证缺权限+网络成功；Push 脱敏阶段计数 |
| F27/F29 | APK 实测 versionCode=1000011、versionName=alpha.11；移除三个无消费者别名/函数 |
| R01 | site-local 与指定特殊地址拒绝测试；保留 DNS 拨号复核、TLS 验证和禁止重定向 |

## 迁移与既有错误归属

- Android v3→v4 增量迁移保留 outbox/inbox/SIM/cursor，模拟器实际升级测试通过。
  旧单卡布尔确认不迁移为永久有效订阅；必须重新确认当前唯一 SIM。旧已 ACK 记录没有 provider 身份，
  不猜测回填；第一次旧历史再导入可能产生旧时间差重复，精确指纹仍幂等。
- MySQL 005 仅新增可重建会话摘要及检查点；实际回填、历史插入不覆盖新消息、旧服务回滚再升级重建测试。
  首次全历史回填需维护窗口，回滚不删除 messages；旧服务与新服务不应同时写同一库。
- 既有错归属先用只读聚合审计，不查看/输出正文，例如：

  ```sql
  SELECT device_id,subscription_id,receiver,COUNT(*) AS messages,
         MIN(timestamp) AS first_at,MAX(timestamp) AS last_at
  FROM messages GROUP BY device_id,subscription_id,receiver;
  ```

  对照换卡时间与用户确认的号码，不能凭卡槽或相同正文推断。确认错误后先备份，单独审批目标记录与改正号码，
  处理指纹唯一键冲突、重新核对会话摘要并生成新恢复代次让客户端重拉；本任务不连接生产、不批量改写历史。

## 边界

- 没有真实 SIM/PDU、厂商 provider、Doze/省电兼容性或公网 Push 验收；跨接口近似匹配仍限定本机同 SIM，
  两条独立 provider 行不合并。provider 清库重建/ID 复用需重新审计，不承诺无限设备生命周期身份不变。
- 撤销前已发出的请求/Push provider 已接受的通知不可撤回；发送前复核缩小窗口，不承诺跨数据库与公网发送原子取消。
- 发布只模拟；部分草稿缺资产或异 digest 必须人工核对，不能自动覆盖。没有 Docker daemon，本次未构建/运行 OCI 栈。
- 普通会话走摘要；正文搜索/来源筛选保留原历史语义，海量排序/搜索、真实磁盘 I/O 和 UI 帧耗时未压测。
- 版本仍为 alpha.11；未创建 PR、合并、发布或部署。任务分支保留供用户授权集成。

## 工作区记录

任务分支 `agent/engineering-audit-remediation`；worktree：
`/home/lvziw/项目/.wt/短信同步系统/engineering-audit-remediation/`。
验证通过后提交/推送该分支；没有创建 PR 的授权，因此不执行 wt done，保留已推送工作区供后续集成。
本轮新建的 MySQL/模拟器测试进程完成后停止，测试产物在仓库外保留；没有复用/清理其他人的工作区。

只读 wt audit 仍有原有 13 个 detached/违规路径，共同前缀：
`/home/lvziw/.local/share/opencode/worktree/6e474b/`。名单：calm-cabin、clever-cactus、clever-comet、
crisp-eagle、hidden-rocket、lucky-nebula、mighty-falcon、nimble-wizard、shiny-lagoon、shiny-panda、
sunny-forest、swift-falcon、witty-canyon。
另保留原分支 `agent/bump-version-alpha-6` 和工作区 `release-workflow-and-signing`（1 个独有提交），均未触碰。
