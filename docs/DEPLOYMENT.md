# 部署与恢复

需要 Docker Engine + Compose v2、Linux 本地磁盘。推荐 HTTPS 域名与反向代理；也支持显式配置外部 HTTP。
不要把 MySQL 数据卷放 SMB/CIFS/NFS：文件锁/刷盘语义会破坏可靠性。Compose 默认只绑定宿主 `127.0.0.1:18473`，转发到容器 `8080`；可通过 `PORT` 覆盖宿主端口。
版本及 Release 状态以根目录 `VERSION` 和 GitHub Release 页面为准；alpha/beta/rc 均为预发布版本，不是稳定版。

```bash
HONGSHU_VERSION=0.1.0-alpha.11 PUBLIC_URL=https://sms.example.com VAPID_SUBJECT=mailto:you@example.com bash scripts/init-deployment.sh
docker compose up -d
curl --fail http://127.0.0.1:18473/healthz
docker compose ps
```

初始化脚本会安全提示输入一次中枢密码，也可通过受保护的环境变量 `HONGSHU_PASSWORD` 提供；弱密码允许，但不能为空。密码保存在权限为 0600 的 `.env`，不要粘贴到日志/issue。Web 登录只需这个密码，无用户名；每次新浏览器登录创建一个管理员设备会话。退出或撤销最后一个管理员后仍可用中枢密码重新登录。

已有部署升级时，在现有 `.env` 中添加 `HONGSHU_PASSWORD`，再重建 hub：`docker compose up -d --force-recreate hub`。原有短信、Android token 和设备记录不变；已登录浏览器的会话不会因更改配置密码而自动撤销，需要在设备页单独撤销旧 Web 会话。

## 外部 HTTP（明文）

仅在你接受明文传输风险时启用。示例将端口发布到所有宿主网卡；仍需自行配置路由器端口转发与防火墙：

```bash
HONGSHU_VERSION=0.1.0-alpha.11 PUBLIC_URL=http://sms.example.com:18473 BIND_ADDRESS=0.0.0.0 PORT=18473 bash scripts/init-deployment.sh
docker compose up -d
curl --fail http://127.0.0.1:18473/healthz
```

`PUBLIC_URL` 必须与浏览器/Android 实际访问的 scheme、主机和端口完全一致。HTTP 会明文传输登录密码、会话凭据、短信正文和 API 数据，链路上的人可以窃听或篡改；弱密码也更容易被猜中。优先使用 HTTPS 或 VPN，不要把 MySQL 3306 暴露到外网。Web Push、Service Worker 与可安装离线应用在公网 HTTP 下不可用。

Android 连接时填写设备名称并保存；连接后也可在中枢设置保存改名。配置 SIM1/SIM2 号码并保存，
或切换自动模式核对匹配后保存。开启“同步本机短信”并授予 RECEIVE_SMS/READ_SMS 后，
全部可读取的历史短信自动分批同步到中枢，断网或重启自动续传，无需手动导入。
自动 SIM 模式会跟踪换卡/号码变化；运营商不提供号码时显示未识别，可切回手动模式。
普通查看设备不需要短信权限。Android 构建见 android/README.md。

## 反向代理示例

Nginx（证书自行提供）：

```nginx
# 在 http {} 内按原始客户端限制昂贵登录，不影响已有会话。
limit_req_zone $binary_remote_addr zone=hongshu_login:10m rate=10r/m;
server {
    listen 443 ssl;
    server_name sms.example.com;
    ssl_certificate /etc/letsencrypt/live/sms.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sms.example.com/privkey.pem;
    client_max_body_size 1m;
    location = /api/login {
        limit_req zone=hongshu_login burst=5 nodelay;
        limit_req_status 429;
        proxy_pass http://127.0.0.1:18473;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        access_log off;
    }
    location / {
        proxy_pass http://127.0.0.1:18473;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        # 覆盖，不追加不可信客户端提交的值。
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 90s;
        # Do not log SMS bodies, authorization, credentials or query searches.
        access_log off;
    }
}
```

HTTPS 反向代理必须设置 `PUBLIC_URL=https://实际域名`，不能保留 HTTP 默认值；这确保 Secure cookie 与 HSTS。
HTTP 局域网访问仍支持自适应 Host。保持默认 `BIND_ADDRESS=127.0.0.1`，代理在别机时用防火墙限制入口。
`TRUSTED_PROXY_CIDRS` 只填写中枢实际看到的代理 peer 的精确 IP `/32` 或 `/128`，不得填所有地址；
Docker 桥接时该地址可能是网关，可由隔离环境的网络配置核对。代理必须覆盖 X-Forwarded-For。
未配置时转发头一律不作为客户端身份，因此代理后的请求会共用 peer 限速桶。
示例已为 `/api/login` 配置独立限速，不影响 WS 和已有身份读取。
不要公开 MySQL 3306。中枢另有全局最多两个并发密码哈希，繁忙返回 503。
Web Push 需要服务器能访问公开 HTTPS Push 服务，浏览器支持且授予通知权限。
iOS Web Push 通常需添加到主屏幕；隐私模式/厂商浏览器可能不支持。在线 WS 失效仍周期补齐。

## 备份

backup 服务每天一次一致性 mysqldump，文件在 `runtime-data/backups/`，不会自动删除。
同时备份 `.env`（含 VAPID 私钥）至加密的异地存储；短信 dump 和身份 token_hash 仍属敏感数据。
磁盘空间需要监控；备份服务日志失败必须处理。定期 `gzip -t`，在隔离数据库实际恢复并核对消息数量、
最高 ID、schema_migrations、设备授权/撤销状态。只检查压缩文件不是完整恢复演练。

恢复不是升级脚本的一部分。**恢复前记录现行库的 epoch（PRE_RESTORE_EPOCH）并保存在不会随数据库回档的记录中**。
无法确定现行代次时先停止恢复，不能把备份里的值当作现行值。停止中枢与 backup，保留当前卷快照/逻辑备份，再将**明确指定的备份**
导入新建的空数据库（不要把 dump 直接覆盖生产）：

```bash
# In an isolated restore deployment with a NEW mysql-data volume:
docker compose up -d db
gzip -dc /secure-backups/hongshu-TIMESTAMP.sql.gz | docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u hongshu hongshu'
# 不要启动 hub；先完成下面的 epoch 步骤。
```

客户端本地游标可能高于恢复后的数据库最高 ID，短信 ID 也会被重用。导入备份后、启动中枢前，
设置为高于**恢复前现行代次与备份代次**的值，避免重复恢复同一备份再次产生相同 epoch；普通升级不改变它：

```bash
restored_epoch=$(docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -s -u hongshu hongshu -e "SELECT epoch FROM sync_clock WHERE id=1"')
new_epoch=$(bash scripts/next-restore-epoch.sh "${PRE_RESTORE_EPOCH:?record the live epoch before restore}" "$restored_epoch")
docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u hongshu hongshu -e "UPDATE sync_clock SET epoch='"$new_epoch"' WHERE id=1"'
# 持久记录 new_epoch，再启动服务。
docker compose up -d hub backup
curl --fail http://127.0.0.1:18473/healthz
```

Android 和已打开的 Web 下次同步会丢掉已同步缓存并从 0 重拉，未上传短信保留。不要为此清除应用数据。
只要执行过数据库恢复就使用新代次，不根据当前 head 是否已经追上旧游标决定是否更改。
最佳做法仍是保留增量/binlog并控制恢复点，而不是回到一个会重用 ID 的旧库。

## 升级与回滚

中枢部署必须选择已发布的固定镜像版本；alpha/beta/rc 没有 latest。用户不需要基于 main 构建。

升级步骤：
1. 停机前做好数据备份；
2. 在 `.env` 中指定目标已发布版本号（例如 `HONGSHU_VERSION=0.1.0-alpha.11`），不自动选择 latest；
3. 拉取新镜像并重启 hub 服务：
   ```bash
   docker compose pull hub
   docker compose up -d hub
   ```
4. 检查 healthz、schema_migrations 和客户端同步状态。

迁移以 checksum + dirty 标记审计，部分 DDL 失败会阻止启动，不能直接清除 dirty 继续。
已有部署会依次应用新增迁移。`002` 给推送任务加租约，`003` 给同步时钟加 epoch，默认都是兼容值。
`005` 创建并回填会话摘要，保留全部消息；首次升级需为全历史回填预留时间和磁盘。该迁移失败仍受 dirty 审计阻止启动。
旧服务可忽略摘要表；重新升级时若摘要检查点落后于提交时钟，中枢会在监听之前持锁重建，
不会把旧服务未维护的摘要当作最新状态。down.sql 仅删除可重建摘要和检查点，不删除 messages。
down.sql 会销毁对应列或全部表，仅能在已确认的测试环境手工使用。
应用回滚只能在 schema 兼容时退回旧版本镜像标签（修改 `.env` 中的 `HONGSHU_VERSION` 并重新 `docker compose up -d hub`）；不兼容时恢复已验证的新卷备份，不能盲目执行 down.sql。
保留数据库与授权信息，`docker compose down` 不加 `-v`；删除数据卷不是升级/回滚步骤。

### 旧版异常会话已读水位

旧版已读接口可能接受不存在或属于其他发件人的消息 ID；升级后的输入校验不会自动改写这些存量水位。
可先只读检查异常数量（不输出号码或短信）：

```sql
SELECT COUNT(*) AS invalid_read_watermarks
FROM conversation_reads cr LEFT JOIN messages m
  ON m.id=cr.last_read_message_id AND m.sender=cr.sender
WHERE cr.last_read_message_id<>0 AND m.id IS NULL;
```

若有异常，先将所有运行中的中枢实例升级到已修复版本并备份数据库，再由部署者显式执行部署包中的修复脚本：

```bash
docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u hongshu hongshu' < scripts/repair-read-watermarks.sql
```

脚本仅将无效会话水位重置为 0，并通知在线客户端重新观察；保留有效水位、全局水位及所有短信。
旧水位原本想表达的已读范围无法还原，部分历史非导入短信可能重新显示未读，用户可重新标读。
脚本不随启动或数据库迁移自动执行；回滚该次修复需使用执行前备份中的对应水位记录。

## 本地开发

Go 1.24+；`MYSQL_DSN`、非空 `HONGSHU_PASSWORD`、可选 VAPID，`PUBLIC_URL=http://localhost:8080`。直接运行 Go 服务时默认监听 8080；Docker Compose 的宿主默认端口为 18473。
从 server 执行 `go run .`；支持 HTTP 的本地 Web 与 Android 联调。
`WEB_DIR` 默认 ../web，`MIGRATIONS_DIR` 默认 ../db/migrations。
生产镜像使用非 root / 只读根文件系统。数据库和备份未做端到端加密，请启用宿主磁盘与备份加密。
## 可选 AI 短信分类配置

升级不会自动启用 AI。Web 管理员在设置中选择 AI 协议，并填写对应的完整接口地址、模型及可选 API 密钥，
再明确勾选历史与新增短信分析说明后开启。
无需 AI 的部署不需要增加任何密钥，验证码规则提取始终可用。

保存 API 密钥前，在未跟踪的部署 `.env` 中设置 `HONGSHU_AI_SECRET`：用
`openssl rand -base64 32` 在自己的终端生成，勿提交或贴到日志/对话中。
该值用于 AES-GCM 加密数据库里的 API 密钥，必须与数据库备份一同安全保管；
丢失后需重新输入 AI API 密钥，不能用新的随机值解密旧数据。

默认只允许公网 HTTPS。使用 Ollama 等本地兼容服务时，将精确主机名加入
`HONGSHU_AI_ALLOWED_HOSTS`（逗号分隔、不支持通配符），才可访问其内网地址或使用 HTTP。
容器中的 localhost 指容器自身，不是宿主。白名单授予该主机内网访问能力，只填可信模型服务。
接口不跟随跳转，不接受 URL 用户凭据、查询参数或片段；API key 必须放专门的密钥字段。

先保存关闭状态的配置，再点击“测试连接（仅合成文本）”；检查成功后开启。
每分钟/每日额度含失败尝试和连接测试，UTC 零点重置每日额度；额度不等于精确货币预算。
鉴权/配置/限流异常会暂停，修正配置后取消暂停或使用“恢复队列并重试失败项”。
每批重试最多 100 条，不会自动重复调用已完成或人工纠正的消息。
模型不保证分类或反诈准确，垃圾不自动删除；脱敏也不能保证匿名。
回滚与数据库迁移说明见 [MESSAGE_GROUPING.md](MESSAGE_GROUPING.md)。
