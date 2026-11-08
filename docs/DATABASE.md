# 数据库与缓存部署

本文说明鸿枢中枢如何连接 MySQL、如何把内置数据库换成外部/共享 MySQL，以及在高延迟链路上
如何避免把数据库往返次数放大。它是 [部署与恢复](DEPLOYMENT.md) 的补充，不替代备份与恢复流程。

## 支持范围

- **MySQL 8.0+**，必须使用 `utf8mb4` / `utf8mb4_bin`。
- 一个中枢实例对应**一个独立 schema**。同一台 MySQL 可以承载多个互不相关的中枢/项目，
  但不要让两个独立中枢共用同一个 schema；那会共享消息、设备、游标和迁移记录。
- 不使用 MariaDB、PostgreSQL 或 SQLite 作为替代后端。现有 SQL、迁移和恢复工具依赖
  MySQL 8 的 `GET_LOCK`、窗口函数、`ON DUPLICATE KEY UPDATE` 和 `utf8mb4_bin` 语义。
- 外部 MySQL 必须允许中枢访问；不要把 3306 暴露到公网。跨公网或不可信网络必须使用 TLS/VPN。

## 配置优先级

中枢按以下顺序读取数据库连接：

1. `HONGSHU_DATABASE_URL`（推荐的外部库形式）；
2. `MYSQL_DSN`（兼容旧部署的 go-sql-driver DSN）；
3. 两者都为空则拒绝启动。

两者同时设置时 URL 生效，并记录一条不含凭据的警告。数据库密码只来自环境变量或未跟踪的
`.env`，不会写入日志、迁移文件或仓库。

## URL 形式

```text
HONGSHU_DATABASE_URL='mysql://user:password@host:3306/database?tls=true&timeout=10s&readTimeout=60s&writeTimeout=60s'
```

- 密码中的 `@`、`:`、`/`、`?`、`#`、`%` 必须做 URL 编码；整个值建议用单引号包住，
  避免 Compose/shell 展开 `$`、`&` 等字符。
- IPv6 地址使用方括号：`mysql://user:pass@[2001:db8::10]:3306/hongshu`。
- 不支持在 URL 中放路径、查询参数或片段以外的额外路径段；数据库名只能有一个。

支持的查询参数：

| 参数 | 说明 |
| --- | --- |
| `tls` | `true`（校验证书）、`false`、`skip-verify`、`preferred`。非回环地址默认 `preferred`；回环地址默认 `false`。 |
| `timeout` | 建立连接/拨号超时，默认 `10s`。 |
| `readTimeout` / `writeTimeout` | 单次 I/O 超时，默认 `60s`；高延迟链路可适当增大。 |
| `interpolateParams` | 默认 `true`。这是降低 RTT 的关键：带参查询不再 prepare/execute/close。 |
| `charset` | 只允许 `utf8mb4`。 |
| `collation` | 只允许 `utf8mb4_bin`。 |
| `maxAllowedPacket` | 可选，最大 1 GiB；默认使用驱动值。 |
| `parseTime` | 只允许 `false`；中枢的时间戳都是 BIGINT 毫秒。 |
| `multiStatements` | 只允许 `false`。批处理使用多行 DML，不拼接多条 SQL。 |

`allowAllFiles`、`allowCleartextPasswords`、`allowFallbackToPlaintext`、`allowOldPasswords`
等不安全选项会被拒绝。

### TLS 与私有 CA

- `tls=true` 使用系统根证书并校验主机名；连接地址是 IP 但证书是 DNS 名时，设置
  `HONGSHU_DB_TLS_SERVER_NAME`。
- 私有 CA：把 PEM 挂载进容器，设置 `HONGSHU_DB_TLS_CA=/path/ca.pem`；该路径必须同时出现在
  Compose volume 中。
- `tls=skip-verify` 和 `tls=preferred` 不提供可靠的服务端认证，只应在明确接受风险时使用。
- 明文连接只应在同一信任网络内使用；中枢启动日志会提示非回环地址未启用 TLS。

## 连接池与超时

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `HONGSHU_DB_MAX_OPEN` | `16` | 最大打开连接数；共享 MySQL 时要计入其他应用和 `max_connections`。 |
| `HONGSHU_DB_MAX_IDLE` | 等于最大打开数 | 空闲连接数。高 RTT 下建议保持等于 max open，避免反复 TCP/TLS/认证。 |
| `HONGSHU_DB_CONN_MAX_LIFETIME` | `30m` | 连接最长生命周期；要短于中间件的空闲断开时间。 |
| `HONGSHU_DB_CONN_MAX_IDLE_TIME` | `5m` | 空闲连接回收时间。 |
| `HONGSHU_DB_STARTUP_TIMEOUT` | `60s` | 启动/迁移命令等待数据库可用的最长时间。 |
| `HONGSHU_MIGRATION_LOCK_TIMEOUT` | `30s` | 等待 schema 级迁移锁的时间。 |

`/healthz` 会返回 `database.open/in_use/idle/max_open/wait_count/wait_ms/latency_ms`，可用于判断
是数据库慢、连接池排队，还是应用自身耗时。它不会返回 DSN、SQL 或短信内容。

## 创建 schema 与账号

外部/共享 MySQL 推荐拆成迁移账号和运行时账号：

```sql
CREATE DATABASE hongshu CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'hongshu_migrate'@'10.0.0.%' IDENTIFIED BY '...';
GRANT CREATE, ALTER, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE
  ON hongshu.* TO 'hongshu_migrate'@'10.0.0.%';
CREATE USER 'hongshu'@'10.0.0.%' IDENTIFIED BY '...';
GRANT SELECT, INSERT, UPDATE, DELETE ON hongshu.* TO 'hongshu'@'10.0.0.%';
```

- 运行时账号不需要 DDL；`SELECT ... FOR UPDATE` 需要 SELECT + UPDATE，已包含在上面的授权中。
- 迁移账号只在执行 `hongshu migrate` 时使用；不要让长期运行的 hub 持有 DDL 权限。
- 主机范围按实际网段填写，不要使用不受限制的 `%` 除非网络已经隔离。

## 迁移模式

| `HONGSHU_MIGRATE` | 行为 | 适用场景 |
| --- | --- | --- |
| `auto`（默认） | 启动时检查并应用未执行的迁移；dirty/checksum 不符或数据库 schema 比程序新时拒绝启动。 | 内置库、单实例、信任 hub 持有 DDL 权限。 |
| `check` | 只检查 schema 是否完整且与程序一致，不执行 DDL；需要已有 `schema_migrations`。 | 外部/共享库，运行时账号只有 DML 权限。 |
| `off` | 跳过迁移检查与执行。 | 明确由外部流程管理 schema 的高级部署；缺少表会在运行时失败。 |

共享 MySQL 推荐流程：

```bash
# 使用迁移账号，执行一次；命令不会启动 HTTP 服务。
HONGSHU_DATABASE_URL='mysql://hongshu_migrate:...@mysql.example:3306/hongshu?...' \
  docker run --rm --env HONGSHU_DATABASE_URL \
  ghcr.io/igngserver/hongshu:0.2.0-alpha.23 migrate

# 运行时账号 + check 模式
HONGSHU_MIGRATE=check docker compose -f compose.external.yml up -d
```

迁移 SQL 已内嵌于同版本镜像的 `/app/migrations`，部署包和空目录初始化都不需要源码树或 `db/migrations`
宿主目录。不要再用宿主 bind mount 覆盖该目录；镜像 digest 与迁移版本必须保持一致。

迁移锁按 schema 名派生，不再是整个 MySQL 服务器共用一把锁；同一台服务器上不同中枢库可以
独立升级。迁移仍以 checksum + dirty 审计，DDL 失败会阻止继续启动，不能直接清除 dirty。

## 部署形态

### 内置 MySQL（默认）

```bash
HONGSHU_VERSION=0.2.0-alpha.23 bash scripts/init-deployment.sh
docker compose up -d
```

`compose.yml` 启动 hub、MySQL 8.4 和每日备份；hub 通过 `MYSQL_DSN` 连接 `db:3306`。

### 外部/共享 MySQL

```bash
HONGSHU_VERSION=0.2.0-alpha.23 \
HONGSHU_DB_MODE=external \
HONGSHU_DATABASE_URL='mysql://hongshu:...@mysql.example:3306/hongshu?tls=true' \
HONGSHU_PASSWORD='...' \
bash scripts/init-deployment.sh
docker compose -f compose.external.yml up -d
```

`compose.external.yml` 只启动 hub，不启动内置 `db` 或 `backup`。如果 MySQL 是同一 Docker
网络中的共享容器，在 Compose override 中增加该 external network；如果在宿主机，Linux 可用
`host.docker.internal:host-gateway` 或宿主 IP。不要把数据库端口映射到公网。

多个应用共用一个 MySQL 时的建议：

- 每个应用一个 schema、一个独立用户；不要跨 schema 查询或共享表。
- 先估算总连接数：每个 hub 默认最多 16 条，按应用数累加后要低于 MySQL `max_connections`，
  并给管理/备份留余量。必要时调低 `HONGSHU_DB_MAX_OPEN`。
- 一个 MySQL 实例共享 `innodb_buffer_pool_size`、磁盘和网络；一个应用的大查询会影响其他应用，
  需要监控慢查询和连接等待。
- 备份按 schema 分别执行；不要让多个应用同时跑全量 `mysqldump`。

### 外部库备份

内置 `backup` 服务只覆盖 Compose 内的 `db`。外部库请使用托管快照或独立备份任务，并定期做
恢复演练。若复用 `scripts/backup.sh`，设置 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_USER`、
`MYSQL_DATABASE`、`MYSQL_PWD`，并给备份容器访问外部库的网络；不要在公网明文传输 dump。
恢复后仍必须遵守 [部署与恢复](DEPLOYMENT.md) 的 `sync_clock.epoch` 规则。

## 高延迟设计

中枢针对“RTT 高、但请求量不大”的场景做了以下约束：

- 上传/通话上传按批提交：一次批量去重、一次多行插入、一次会话头 upsert、一次 revision 区间分配，
  不再每条短信 7–8 条 SQL。100 条短信的 SQL 数量从约 700–800 条降到十几条。
- `interpolateParams=true` 去掉带参查询的 prepare/execute/close 往返。
- `/api/me` 用一条 JOIN 读取所有单行状态；未读数缓存不再在持有互斥锁时执行 COUNT。
- WebSocket 每个进程只用一个共享状态轮询器，不再每个连接每 25 秒查一次库；连接仍定期校验设备撤销。
- AI 回填按批分配 revision，完成后停止每秒事务；AI 分析、Push 在空闲时等待唤醒，而不是固定 1s/3s 轮询。
- 元数据（设备/SIM/联系人）按 `metadata_clock.revision` 做进程内缓存；联系人不会进入 Redis。
- 迁移 014 增加 `messages(historical,direction,id,sender)` 和 `push_jobs(next_at,leased_until,id)` 索引。

这些改动不改变提交顺序、幂等键、epoch、已读水位或 Push 至少一次投递语义。

## 可选 Redis

Redis 不是必需组件。单 hub 部署优先使用进程内缓存；Redis 主要解决：

- 多中枢副本共享同一 schema 时的跨实例变更提示；
- 多实例 AI worker 的选主，避免重复调用；
- 高 RTT 数据库前的共享未读数缓存。

配置：

```text
HONGSHU_REDIS_URL=redis://:password@redis.example:6379/0
# 多个 hub/应用共用一个 Redis 时必须使用不同前缀；留空时按数据库名派生。
HONGSHU_REDIS_PREFIX=hongshu:
```

- 只缓存非敏感派生数据：未读数、变更事件、worker 选主。短信正文、号码、联系人、token、
  AI secret、Push endpoint **不会进入 Redis**。
- 缓存 key 包含 `epoch/head/watermark/revision`；Redis 故障时回退进程内缓存和数据库轮询，
  不影响短信写入或启动。启动时 Redis ping 失败只会禁用缓存并记录警告。
- 多实例共享同一 Redis 时，同一 schema 的副本使用相同前缀；不同项目使用不同前缀/ACL。
- Redis 不应保存唯一数据源；不要把 `FLUSHALL`、共享 DB index 或无认证端口作为默认配置。
- 单实例不需要 Redis；启用后 `/healthz` 的 `redis.enabled` 只表示配置存在，不表示每条缓存命中。

## 从内置库迁移到外部库

1. 停 hub 和 backup，保留旧 volume；
2. 用 `mysqldump --single-transaction` 导出，导入外部空 schema；
3. 核对 `schema_migrations`、消息数量、`sync_clock.epoch`；
4. 在 `.env` 设置 `HONGSHU_DATABASE_URL`，改用 `compose.external.yml` 启动；
5. 检查 `/healthz`、客户端同步和备份任务；确认后再清理旧 volume。

直接搬库不会改变 epoch，客户端不需要重置。若从旧备份恢复，必须按现有恢复流程生成更高的
`sync_clock.epoch`。

## 故障排查

| 现象 | 优先检查 |
| --- | --- |
| `authentication failed` | 用户名/密码、URL 编码、MySQL 认证插件；日志不会输出密码。 |
| `unknown database` | schema 是否已创建，URL 路径是否只有一个数据库名。 |
| `database must use utf8mb4/utf8mb4_bin` | 用 `CREATE DATABASE ... CHARACTER SET utf8mb4 COLLATE utf8mb4_bin` 重建；不要只改连接参数。 |
| `TLS negotiation failed` | `tls=true/false`、CA 挂载、`HONGSHU_DB_TLS_SERVER_NAME`；自签证书不要误用 `skip-verify` 代替校验。 |
| `migration lock unavailable` | 另一个 hub 正在迁移该 schema；等待或检查残留进程。 |
| `schema contains migration ... that this hub does not know` | 数据库比当前镜像新；升级 hub，不要用旧版本继续写入。 |
| `wait_count` 持续增长 | 连接池太小或数据库慢；先看 `latency_ms`、慢查询和共享 MySQL 的其他应用。 |
| 外部库上传仍慢 | 检查是否真的设置了 `HONGSHU_DATABASE_URL`、是否走了旧 `MYSQL_DSN`、`interpolateParams` 是否被覆盖，以及迁移 014 是否已应用。 |
