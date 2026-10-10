# 部署与恢复

需要 Docker Engine + Compose v2、Linux 本地磁盘。推荐 HTTPS 域名与反向代理；也支持显式配置外部 HTTP。
不要把 MySQL 数据卷放 SMB/CIFS/NFS：文件锁/刷盘语义会破坏可靠性。Compose 默认只绑定宿主 `127.0.0.1:18473`，转发到容器 `8080`；可通过 `PORT` 覆盖宿主端口。
版本及 Release 状态以根目录 `VERSION` 和 GitHub Release 页面为准；alpha/beta/rc 均为预发布版本，不是稳定版。

```bash
PUBLIC_URL=https://sms.example.com VAPID_SUBJECT=mailto:you@example.com bash scripts/init-deployment.sh
docker compose up -d
curl --fail http://127.0.0.1:18473/healthz
docker compose ps
```

初始化脚本会安全提示输入一次中枢密码，也可通过受保护的环境变量 `HONGSHU_PASSWORD` 提供；弱密码允许，但不能为空。密码保存在权限为 0600 的 `.env`，不要粘贴到日志/issue。Web 登录只需这个密码，无用户名；每次新浏览器登录创建一个管理员设备会话。退出或撤销最后一个管理员后仍可用中枢密码重新登录。

已有部署升级时，在现有 `.env` 中添加 `HONGSHU_PASSWORD`，再重建 hub：`docker compose up -d --force-recreate hub`。原有短信、Android token 和设备记录不变；已登录浏览器的会话不会因更改配置密码而自动撤销，需要在设备页单独撤销旧 Web 会话。

## 外部 HTTP（明文）

仅在你接受明文传输风险时启用。示例将端口发布到所有宿主网卡；仍需自行配置路由器端口转发与防火墙：

```bash
PUBLIC_URL=http://sms.example.com:18473 BIND_ADDRESS=0.0.0.0 PORT=18473 bash scripts/init-deployment.sh
docker compose up -d
curl --fail http://127.0.0.1:18473/healthz
```

`PUBLIC_URL` 必须与浏览器/Android 实际访问的 scheme、主机和端口完全一致。HTTP 会明文传输登录密码、会话凭据、短信正文和 API 数据，链路上的人可以窃听或篡改；弱密码也更容易被猜中。优先使用 HTTPS 或 VPN，不要把 MySQL 3306 暴露到外网。Web Push、Service Worker 与可安装离线应用在公网 HTTP 下不可用。

Android 连接后打开本机采集开关、授予 RECEIVE_SMS、识别/确认每张 SIM 号码，再选择历史导入。
普通查看设备不需要短信权限。Android 构建见 android/README.md。

## 反向代理示例

Nginx（证书自行提供）：

```nginx
server {
    listen 443 ssl;
    server_name sms.example.com;
    ssl_certificate /etc/letsencrypt/live/sms.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sms.example.com/privkey.pem;
    client_max_body_size 1m;
    location / {
        proxy_pass http://127.0.0.1:18473;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 90s;
        # Do not log SMS bodies, authorization, credentials or query searches.
        access_log off;
    }
}
```

PUBLIC_URL 可设置为反向代理域名或保持默认 `http://127.0.0.1:18473`；中枢支持自适应 Host 与局域网 IP 直连访问，对跨域恶意站点保留 CSRF 拦截。HTTPS 反向代理场景保持默认 `BIND_ADDRESS=127.0.0.1`；代理在另一台主机时显式调整绑定并用防火墙限制中枢端口，不公开 MySQL 3306。
Web Push 需要服务器能访问公开 HTTPS Push 服务，浏览器支持且授予通知权限。
iOS Web Push 通常需添加到主屏幕；隐私模式/厂商浏览器可能不支持。在线 WS 失效仍周期补齐。

## 备份

backup 服务每天一次一致性 mysqldump，文件在 `runtime-data/backups/`，不会自动删除。
同时备份 `.env`（含 VAPID 私钥）至加密的异地存储；短信 dump 和身份 token_hash 仍属敏感数据。
磁盘空间需要监控；备份服务日志失败必须处理。定期 `gzip -t`，在隔离数据库实际恢复并核对消息数量、
最高 ID、schema_migrations、设备授权/撤销状态。只检查压缩文件不是完整恢复演练。

恢复不是升级脚本的一部分。停止中枢与 backup，保留当前卷快照/逻辑备份，再将**明确指定的备份**
导入新建的空数据库（不要把 dump 直接覆盖生产）：

```bash
# In an isolated restore deployment with a NEW mysql-data volume:
docker compose up -d db
gzip -dc /secure-backups/hongshu-TIMESTAMP.sql.gz | docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u hongshu hongshu'
docker compose up -d hub backup
curl --fail http://127.0.0.1:18473/healthz
```

客户端本地游标可能高于恢复后的数据库最高 ID，短信 ID 也会被重用。导入备份后、启动中枢前，
把 epoch 加一（不要自动加，也不要在普通升级时加）：

```bash
docker compose exec db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u hongshu hongshu -e "UPDATE sync_clock SET epoch=epoch+1 WHERE id=1"'
```

Android 和已打开的 Web 下次同步会丢掉已同步缓存并从 0 重拉，未上传短信保留。不要为此清除应用数据。
若恢复的是当前库的完整连续备份、客户端游标没有越过恢复点，可以不加 epoch；无法确认时就加。
最佳做法仍是保留增量/binlog并控制恢复点，而不是回到一个会重用 ID 的旧库。

## 升级与回滚

中枢部署基于发布镜像标签（如 `latest` 或指定版本号如 `0.1.0-alpha.5`），用户不需要克隆源码或基于 `main` 分支构建。

升级步骤：
1. 停机前做好数据备份；
2. 在 `.env` 中指定目标版本号（例如 `HONGSHU_VERSION=0.1.0-alpha.5`）或保持 `HONGSHU_VERSION=latest`；
3. 拉取新镜像并重启 hub 服务：
   ```bash
   docker compose pull hub
   docker compose up -d hub
   ```
4. 检查 healthz、schema_migrations 和客户端同步状态。

迁移以 checksum + dirty 标记审计，部分 DDL 失败会阻止启动，不能直接清除 dirty 继续。
已有部署会依次应用新增迁移。`002` 给推送任务加租约，`003` 给同步时钟加 epoch，默认都是兼容值。
down.sql 会销毁对应列或全部表，仅能在已确认的测试环境手工使用。
应用回滚只能在 schema 兼容时退回旧版本镜像标签（修改 `.env` 中的 `HONGSHU_VERSION` 并重新 `docker compose up -d hub`）；不兼容时恢复已验证的新卷备份，不能盲目执行 down.sql。
保留数据库与授权信息，`docker compose down` 不加 `-v`；删除数据卷不是升级/回滚步骤。

## 本地开发

Go 1.24+；`MYSQL_DSN`、非空 `HONGSHU_PASSWORD`、可选 VAPID，`PUBLIC_URL=http://localhost:8080`。直接运行 Go 服务时默认监听 8080；Docker Compose 的宿主默认端口为 18473。
从 server 执行 `go run .`；支持 HTTP 的本地 Web 与 Android 联调。
`WEB_DIR` 默认 ../web，`MIGRATIONS_DIR` 默认 ../db/migrations。
生产镜像使用非 root / 只读根文件系统。数据库和备份未做端到端加密，请启用宿主磁盘与备份加密。
