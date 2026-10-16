# Albion Guild Attendance

## 综述

阿尔比恩公会每日参战统计服务，使用 **Java 21 + Spring Boot**，支持亚洲、欧洲和美洲服务器。可独立部署，也可通过只读 HTTP 接口与 AlbionWeb 或后续前端页面对接。

服务根据配置的公会名称或 ID 拉取成员名单与官方战报，筛选同时达到“最小本会人数”和“最小战役人数”的战役，按玩家 ID 统计参战与未记录参战人员，保存到 MySQL，并生成按日期累计的 UTF-8 TXT。

每天按配置的 **UTC+0** 时间生成当天快照，次日补齐前一天全天。同一天重跑更新该日记录，不重复追加。战役按开始时间归属 UTC 日期；未记录参战名单以采集时的公会成员快照为准，参战名单以官方战报记录为准。战报参战但当前已不在公会的人员单独展示。

提供以下只读接口，均面向配置中的公会：

| 接口 | 用途 |
| --- | --- |
| `GET /api/reports?limit=31` | 查询最近有日报的 UTC 日期，倒序返回，limit 范围 1–366 |
| `GET /api/reports/latest` | 查询最新日报，含成员、战役和统计人数 |
| `GET /api/reports/2026-10-15` | 查询指定 UTC 日期的日报 |
| `GET /api/reports/txt` | 查看或下载累计 TXT |

日报 JSON 使用 `guild_id`、`cutoff_utc`、`members`、`battles`、`counts` 等字段，便于前端直接读取。没有数据时返回 404。服务不提供公开的写入接口，手动采集通过命令行执行。

许可证：[MIT](LICENSE)。

## 部署教程

### Docker Compose（推荐）

需要 Docker Engine 和 Compose 插件。

```sh
git clone https://github.com/MIKADO6/albion-guild-attendance.git
cd albion-guild-attendance
cp config/application.example.yml config/application.yml
cp .env.example .env
```

编辑 `config/application.yml`，填写服务器、公会、人数门槛和 UTC 抓取时间。示例配置使用欧洲服 `CHINAFIGHT`。编辑 `.env`，为 `MYSQL_PASSWORD` 和 `MYSQL_ROOT_PASSWORD` 设置不同的随机长密码。

```sh
docker compose up -d --build
docker compose logs -f collector
```

部署包含 MySQL 8.4 和 Java 服务，数据库及 TXT 使用持久卷保存。MySQL 不向宿主机开放端口；HTTP 默认绑定宿主机 `127.0.0.1:8080`。

立即采集当天快照：

```sh
docker compose run --rm collector --attendance.command=fetch
```

打开 `http://127.0.0.1:8080/api/reports/txt` 查看累计名单，或打开 `/api/reports/latest` 查看 JSON。首次抓取前可能返回 404。需要远程访问时，通过现有 HTTPS 反向代理提供访问入口；也可由 AlbionWeb 服务端调用查询接口。

补算指定 UTC 日期（替换为最近六个已结束日期之一）：

```sh
docker compose run --rm collector --attendance.command=fetch --attendance.date=2026-10-15
```

从数据库重新生成 TXT，不访问 GameInfo：

```sh
docker compose run --rm collector --attendance.command=export
```

修改配置后运行 `docker compose restart collector`。更新代码后运行 `docker compose up -d --build`。停止服务用 `docker compose down`，保留历史数据时不要使用 `down -v`。

### JAR 部署

需要 JDK 21、Maven 3.9+ 和 MySQL 8.0/8.4。先创建数据库和专用用户，替换示例密码：

```sql
CREATE DATABASE albion_attendance CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'attendance'@'localhost' IDENTIFIED BY 'REPLACE_WITH_RANDOM_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE ON albion_attendance.* TO 'attendance'@'localhost';
```

在项目目录执行：

```sh
mvn -B clean package
cp config/application.example.yml config/application.yml
export MYSQL_URL='jdbc:mysql://localhost:3306/albion_attendance?characterEncoding=UTF-8&connectionTimeZone=UTC'
export MYSQL_USER='attendance'
export MYSQL_PASSWORD='YOUR_DATABASE_PASSWORD'
java -jar target/guild-attendance.jar
```

服务默认读取工作目录下的 `config/application.yml`。也可以使用 `--spring.config.additional-location=file:/path/application.yml` 指定其他配置文件。使用 systemd 或其他进程管理工具托管，并配置固定工作目录和上述环境变量。

手动采集和导出：

```sh
java -jar target/guild-attendance.jar --attendance.command=fetch
java -jar target/guild-attendance.jar --attendance.command=fetch --attendance.date=2026-10-15
java -jar target/guild-attendance.jar --attendance.command=export
```

PowerShell 中使用 `$env:MYSQL_PASSWORD = 'YOUR_DATABASE_PASSWORD'` 设置环境变量；Java 和 Maven 命令相同。JAR 模式不会自动加载 `.env`。

## 配置

完整配置见 [config/application.example.yml](config/application.example.yml)。

```yaml
attendance:
  server: europe
  guild-id: "lrRaQxznSiC_yZI1Sepg0g"
  guild-name: ""
  fetch-time-utc: "23:30"
  min-guild-players: 5
  min-battle-players: 10
  output-directory: ./output
  scheduling-enabled: true
  finalize-previous-day: true
  retry-delay-seconds: 300
```

| 配置项（位于 `attendance` 下） | 含义 |
| --- | --- |
| `server` | `asia` 亚洲 / `europe` 欧洲 / `americas` 美洲 |
| `guild-id` / `guild-name` | 至少填写一个；同时填写时必须匹配同一公会，建议固定 ID |
| `fetch-time-utc` | 每日拉取时间，严格使用 UTC `HH:mm` |
| `min-guild-players` | 每场最小本会人数，默认 5 |
| `min-battle-players` | 每场最小总人数，默认 10 |
| `output-directory` | TXT 输出目录，相对服务工作目录，默认 `./output` |
| `scheduling-enabled` | 是否启用每天自动采集，默认 true |
| `finalize-previous-day` | 是否补齐前一天全天，默认 true |
| `retry-delay-seconds` | 每日任务失败后的最小重试间隔，默认 300 秒 |
| `api.timeout-seconds` / `api.retries` | HTTP 超时 30 秒、最多重试 3 次 |
| `api.retry-seconds` | HTTP 重试初始间隔，默认 1 秒，逐次增加 |
| `api.page-limit` / `api.max-pages` | 每页 50 条、最多 200 页；超过上限报错而非保存截断结果 |

两个参战人数门槛须同时满足，等于门槛也纳入统计。公会名称精确匹配并忽略大小写；填写 ID 时可将名称留空。`23:30` UTC 对应新加坡/北京时间次日 `07:30`。程序每 30 秒检查一次是否到达计划时间，启动时已过当天计划时间则补执行。

| 环境变量 | 含义 |
| --- | --- |
| `MYSQL_URL` | JDBC 连接地址；Compose 已设置为内部 MySQL 服务 |
| `MYSQL_USER` | 数据库用户名，默认 attendance |
| `MYSQL_PASSWORD` | 数据库密码 |
| `MYSQL_ROOT_PASSWORD` | 仅用于 Compose 初始化 MySQL 管理员 |
| `HTTP_BIND_ADDRESS` | JAR 默认 127.0.0.1；容器内为 0.0.0.0 |
| `HTTP_PORT` | HTTP 端口，默认 8080 |

实际配置、`.env`、构建产物和名单输出均被 Git 忽略。后续前端建议经同源反向代理调用接口；与 AlbionWeb 对接时，可以保持服务独立部署并使用 HTTP 接口，也可按业务需要复用 `core` 中的战报采集与统计代码。
