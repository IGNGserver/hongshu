# 部署与恢复

需要 Docker Engine + Compose v2、Linux 本地磁盘、HTTPS 域名与反向代理。
不要把 MySQL 数据卷放 SMB/CIFS/NFS：文件锁/刷盘语义会破坏可靠性。中枢默认只监听宿主 127.0.0.1。
源码从已审计的开发分支检出；本轮没有 tag 或 Release，不要称为已发布稳定版。

```bash
PUBLIC_URL=https://sms.example.com VAPID_SUBJECT=mailto:you@example.com bash scripts/init-deployment.sh
docker compose up -d
curl --fail http://127.0.0.1:8080/healthz
docker compose ps
```

首次浏览器打开 HTTPS 地址，选择“首次初始化中枢”，从本机受保护 `.env` 读取
BOOTSTRAP_SECRET（不要粘贴到日志/issue），创建管理员。随后在设备页生成配对码供其他
Web 和 Android 使用。配对码仅十分钟且只能使用一次。建议另配对一个备用管理员浏览器，
设备页“生成管理员配对码”明确授予管理权；最后一个管理员不能从 UI 退出或被撤销。
丢失唯一管理员凭据时必须先备份，
再按数据库维护方式由部署者处理；系统没有万能登录后门。

Android 配对后打开本机采集开关、授予 RECEIVE_SMS、识别/确认每张 SIM 号码，再选择历史导入。
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
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 90s;
        # Do not log SMS bodies, authorization, pairing codes or query searches.
        access_log off;
    }
}
```

PUBLIC_URL 必须与浏览器访问 origin 完全一致；不可放子路径；不开放 CORS。
需要放在另一台代理主机时显式调整绑定并用防火墙限制中枢端口，不公开 MySQL 3306。
Web Push 需要服务器能访问公开 HTTPS Push 服务，浏览器支持且授予通知权限。
iOS Web Push 通常需添加到主屏幕；隐私模式/厂商浏览器可能不支持。在线 WS 失效仍周期补齐。

## 备份

backup 服务每天一次一致性 mysqldump，文件在 `runtime-data/backups/`，不会自动删除。
同时备份 `.env`（含 VAPID 私钥）至加密的异地存储；短信 dump 和身份 token_hash 仍属敏感数据。
磁盘空间需要监控；备份服务日志失败必须处理。定期 `gzip -t`，在隔离数据库实际恢复并核对消息数量、
最高 ID、schema_migrations、配对/撤销状态。只检查压缩文件不是完整恢复演练。

恢复不是升级脚本的一部分。停止中枢与 backup，保留当前卷快照/逻辑备份，再将**明确指定的备份**
导入新建的空数据库（不要把 dump 直接覆盖生产）：

```bash
# In an isolated restore deployment with a NEW mysql-data volume:
docker compose up -d db
gzip -dc /secure-backups/hongshu-TIMESTAMP.sql.gz | docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u hongshu hongshu'
docker compose up -d hub backup
curl --fail http://127.0.0.1:8080/healthz
```

客户端本地游标可能高于恢复后的数据库最高 ID：恢复后必须重新配对客户端（Android 清除本地历史缓存
或重新安装；不要在尚有未上传短信时清除应用数据）。为避免回档后 ID 重用，最佳做法是从完整备份恢复，
保留增量/binlog并控制恢复点；首版没有自动跨数据库回档的 epoch 协议。

## 升级与回滚

停机前备份，记录旧镜像 ID：`docker compose images`。从明确提交构建新镜像，
`docker compose up -d --build hub`，检查 healthz、schema_migrations 和客户端同步。
迁移以 checksum + dirty 标记审计，部分 DDL 失败会阻止启动，不能直接清除 dirty 继续。
本版只有初始迁移，没有旧业务数据升级路径；down.sql 会销毁全部表，仅能在已确认的测试环境使用。
应用回滚只能在 schema 兼容时用旧镜像；不兼容时恢复已验证的新卷备份，不能盲目执行 down.sql。
保留数据库与授权信息，`docker compose down` 不加 `-v`；删除数据卷不是升级/回滚步骤。

## 本地开发

Go 1.24+；`MYSQL_DSN`、`BOOTSTRAP_SECRET`、可选 VAPID，`PUBLIC_URL=http://localhost:8080`。
从 server 执行 `go run .`；本地 HTTP 仅 Web 调试，Android 故意禁止 HTTP。
`WEB_DIR` 默认 ../web，`MIGRATIONS_DIR` 默认 ../db/migrations。
生产镜像使用非 root / 只读根文件系统。数据库和备份未做端到端加密，请启用宿主磁盘与备份加密。
