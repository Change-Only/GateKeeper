# GateKeeper — 企业级 APIM 统一接口管理平台

> **接口资产 + 应用身份 + 授权关系** 的统一管理面，网关作为执行面落地管控策略。

GateKeeper 把企业内部各后端系统的接口统一登记、集中授权，并对每一次外部调用做完整管控：**谁**（应用身份/凭证）可以调用**什么**（接口资产）、**从哪里**（IP 白名单/封禁）、**多久一次**（限流/配额）、**传输是否加密**（国密 SM2/SM3/SM4 与 AES）、**异常是否可发现**（异常检测/告警/封禁）。所有调用全程留痕，可审计、可追溯、可视化。

---

## 核心能力

| 模块 | 说明 |
|------|------|
| 环境隔离 | 环境（dev/test/prod）多级隔离，接口按环境配置差异化参数与地址；应用凭证与授权均带 `env_code` |
| 接口资产 | 接口注册、分组、版本管理、参数定义（含导入）、环境配置、上下线、变更历史与灰度 |
| 应用与凭证 | 应用注册、AppKey/AppSecret 签发、多环境凭证、凭证轮换/吊销、到期时间管理 |
| 授权关系 | 应用-接口授权、按分组批量授权、授权审批与回收、数据权限（DataScope） |
| 网关执行面 | IP 白名单 → 封禁检查 → 应用校验（签名/时间戳/Nonce 防重放）→ 限流 → 权限校验 → 入参解密 → 转发 → 响应加密 → 调用日志 |
| 加解密 | SM2 / SM3 / SM4 国密算法 + AES / MD5 / SHA256，按应用与接口维度独立配置，密钥落库加密存储 |
| 安全防护 | 异常调用检测（高频 / 异常时段 / 连续鉴权失败 / 异常入参 / 权限越界）、IP 封禁（手动 + 自动）、告警通知多渠道 |
| 告警中心 | 按等级（INFO/WARNING/CRITICAL）汇聚网关内部错误、限流、自动封禁、异常入参等告警；未读统计、已读处置、顶栏铃铛实时提示 |
| 调用日志 | 全链路记录：应用、接口、入参、响应、耗时、状态码、来源 IP、加密算法、限流/拦截标记；支持异步导出 CSV |
| 统计与大屏 | 调用量趋势、应用排行、接口热度、错误率、耗时分布（P50/P90/P99）；大屏投屏展示 |
| 权限体系 | RBAC 菜单/按钮权限（`sys_menu` 当前播种 **74** 个权限码）、`@RequirePerm` 注解式服务端强校验、Redis 权限缓存 |
| 系统管理 | 系统参数、数据字典、用户/角色、操作审计日志、安全策略 |

---

## 界面预览

> 以下截图取自**本地真实运行环境**（`init.sql` + 迁移脚本 + 一批演示数据），由无头 Chrome 逐页实拍，非设计稿、非原型图。
> 演示数据仅用于截图展示（应用 / 接口 / 分组 / 授权 / 用户 / 封禁），不随仓库分发。

### 登录与概览

| 登录页 | 概览（待办清单 / 风险看板 / 调用趋势） |
|:---:|:---:|
| ![登录页](docs/screenshots/01-login.png) | ![概览](docs/screenshots/02-dashboard.png) |

### 接口资产

| 接口列表（路径 / 方法 / 分组 / 后端地址 / 上下线） | 接口详情（参数定义 · 版本管理 · 环境配置 · 变更历史） |
|:---:|:---:|
| ![接口列表](docs/screenshots/05-api-list.png) | ![接口详情](docs/screenshots/24-api-detail.png) |

| 接口分组（多级树） | 应用列表（AppKey / 状态 / 到期时间） |
|:---:|:---:|
| ![接口分组](docs/screenshots/04-api-group.png) | ![应用列表](docs/screenshots/03-app-list.png) |

### 授权与权限体系

| 接口授权总览（App × 接口矩阵，批量治理与越权排查） | 用户管理 |
|:---:|:---:|
| ![接口授权总览](docs/screenshots/06-perm-matrix.png) | ![用户管理](docs/screenshots/07-perm-user.png) |

| 角色管理 | 数据权限 |
|:---:|:---:|
| ![角色管理](docs/screenshots/08-perm-role.png) | ![数据权限](docs/screenshots/09-perm-datascope.png) |

| 操作审计 |
|:---:|
| ![操作审计](docs/screenshots/10-perm-audit.png) |

### 网关安全与防护

| 安全策略（异常检测 / IP 封禁 / 告警） | 封禁规则（动态规则 · 触发原因 · 阈值 · 时长） |
|:---:|:---:|
| ![安全策略](docs/screenshots/12-sys-security.png) | ![封禁规则](docs/screenshots/20-mon-block.png) |

| 封禁名单（自动 / 人工封禁记录） | 告警规则 |
|:---:|:---:|
| ![封禁名单](docs/screenshots/25-mon-block-bans.png) | ![告警规则](docs/screenshots/13-sys-alarm.png) |

### 加解密管理

| 加解密管理（全局开关 · 应用 / 分组 / 接口三级覆盖 · 国密 SM2/SM3/SM4） |
|:---:|
| ![加解密管理](docs/screenshots/21-encryption.png) |

### 监控、日志与大屏

| 调用日志（全链路留痕，支持异步导出 CSV） | 告警记录 |
|:---:|:---:|
| ![调用日志](docs/screenshots/18-mon-calllog.png) | ![告警记录](docs/screenshots/19-mon-alarm.png) |

| 日志与审计 | 数据大屏（投屏展示） |
|:---:|:---:|
| ![日志与审计](docs/screenshots/17-sys-log.png) | ![数据大屏](docs/screenshots/22-screen.png) |

### 系统设置

| 环境与网关 | 通知渠道（企微 / 钉钉 / 邮件 / Webhook） |
|:---:|:---:|
| ![环境与网关](docs/screenshots/11-sys-env.png) | ![通知渠道](docs/screenshots/14-sys-notify.png) |

| 参数配置（SECURITY / GATEWAY / LOG / DEFAULT 分组，敏感项掩码） | 字典管理 |
|:---:|:---:|
| ![参数配置](docs/screenshots/15-sys-config.png) | ![字典管理](docs/screenshots/16-sys-dict.png) |

### 开发者中心

| 接入文档（签名规则 / 调用示例） |
|:---:|
| ![接入文档](docs/screenshots/23-access-doc.png) |

---

## 技术栈

| 层面 | 技术选型 | 版本 |
|------|---------|------|
| 后端框架 | Spring Boot | 2.7.18 |
| 语言 / JDK | Java | **1.8** |
| 持久层 | MyBatis-Plus（注解式，无 XML） | 3.5.x |
| 数据库 | MySQL | **8.x** |
| 缓存 / 限流 / 防重放 | Redis + Lettuce | 6+ |
| 加解密 | Bouncy Castle（SM2/SM3/SM4）+ JCE（AES） | bcprov-jdk15to18 |
| 工具库 / 转发 | Hutool / Apache HttpClient | — |
| 接口文档 | Knife4j（OpenAPI3） | — |
| 构建 | Maven | 3.8+ |
| 前端框架 | Vue + Element UI + Vue Router + Vuex | 2.7.14 / 2.15.14 / 3.6.5 / 3.6.2 |
| 前端图表 / 请求 | ECharts / Axios | 5.x / 0.27.x |
| 前端构建 | Vue CLI（`@vue/cli-service`） | ~5.0.0 |
| 部署 | Docker Compose（MySQL + Redis + 后端 + Nginx） | — |

**代码规模**：后端主代码 **297** 个 `.java`，单元测试 **86** 个 `.java`（`mvn -o test` 跑 **773** 个用例，全绿）；前端 **50** 个 `.vue`。

> 📌 本节所有数字核对于 2026-09-18。业务全景与核心流程另见 [`docs/GateKeeper-业务与核心流程.md`](docs/GateKeeper-业务与核心流程.md)。

---

## 目录结构

```
GateKeeper/
├── src/
│   ├── backend/                     # Spring Boot 2.7 后端（Java 8）
│   │   ├── pom.xml                  # 打包产物 target/gatekeeper.jar
│   │   ├── Dockerfile               # 多阶段构建（maven 编译 → JRE 运行）
│   │   └── src/
│   │       ├── main/java/com/gatekeeper/
│   │       │   ├── controller/      # 24 个 REST 控制器（管理面）
│   │       │   ├── service/         # 业务逻辑层
│   │       │   ├── mapper/          # MyBatis-Plus Mapper（注解实现）
│   │       │   ├── entity/          # 实体类
│   │       │   ├── gateway/         # 网关执行面
│   │       │   │   ├── handler/     # 责任链：IP白名单/封禁/应用校验/限流/权限/加解密/转发/日志
│   │       │   │   └── EnvResolver.java
│   │       │   ├── crypto/          # SM2/SM3/SM4/AES 加解密
│   │       │   ├── security/        # 异常检测、IP 封禁、告警
│   │       │   ├── job/             # 定时任务（告警评估、授权过期、日志保留、配额重置）
│   │       │   ├── annotation/      # @RequirePerm 等
│   │       │   └── config/          # 安全自检、CORS、拦截器、MyBatis-Plus、线程池
│   │       ├── main/resources/
│   │       │   ├── application.example.yml  # 🔴 配置模板（可提交，无秘密）
│   │       │   ├── application.yml          # 🔴 本地真实配置（.gitignore 忽略，不入库）
│   │       │   ├── mapper/                  # 预留目录（当前无 XML）
│   │       │   └── sql/init.sql             # 数据库初始化脚本（建表 + 基础种子数据）
│   │       └── test/java/                   # 86 个单元测试文件（773 个用例）
│   └── frontend/                    # Vue 2 前端
│       ├── package.json             # scripts: serve / build / lint
│       ├── vue.config.js            # devServer 端口 8081，/api → http://localhost:8080
│       ├── nginx.conf               # 生产静态资源 + /api 反代
│       ├── Dockerfile
│       └── src/                     # views（页面）/ components / api / router / store / utils
├── docs/                            # 产品与架构文档（PRD、架构设计、契约记录、SQL 等）
├── docker/                          # 辅助部署配置（mock-upstream.conf，E2E 用）
├── design/                          # UI 设计稿与规范
├── security/                        # 安全测评报告
├── docker-compose.yml               # 一键部署编排
├── .env.example                     # 部署环境变量模板
└── README.md
```

---

## 本地启动方法

> 命令中 `<...>` 为占位符，请按实际环境替换。建议 Linux/macOS 或 Git Bash 环境；Windows PowerShell 的差异点会在步骤中标注。

**验证范围声明**（便于你判断每条命令的可信度——标注为"未实测"的请在你的机器上验证）：

| 内容 | 状态 | 验证方式与范围 |
|------|------|---------------|
| 后端启动 + 登录（步骤 4 / 6 / 7） | ✅ **已实测** | 本机后端起于 `8080`（context-path `/api`）；`POST /api/auth/login` 返回 `code=200`、`message=success`、`permCount=86`、`token` 长 141 |
| 前端 dev server（步骤 5 / 7） | ✅ **已实测** | `:8081` 返回 HTTP 200；`npm run build -- --no-clean` 输出 `DONE Build complete`（Time 8245ms、Hash ec872ce6a202be04） |
| Redis 连通（步骤 2） | ✅ **已实测** | 原生 TCP 发送 `PING`，收到 `+PONG` |
| `mvn` 标准命令与 classworlds 兜底（步骤 4） | ✅ **已实测** | 本机 `mvn -v` 复现 `找不到或无法加载主类 ...Launcher`；兜底写法返回 `Apache Maven 3.8.8` / `Java 1.8.0_391`，并成功执行 `compile`（退出码 0） |
| `mysql` 导入命令（步骤 1） | ⚠️ **未实测** | 本机未安装 `mysql` 客户端，无法执行。命令形式按 MySQL 官方语法与 `init.sql` 头部建库/建表语句核对得出 |
| Docker Compose 一键部署 | ⚠️ **未实测** | 本环境未提供 Docker（`docker --version` → `command not found`）。见该章节的声明 |
| 其余各平台 Redis/MySQL 启动命令 | ⚠️ **未实测** | 属于各平台通用标准命令，非在单一机器上逐条执行 |

> 上表中「已实测」的前提是：后端与前端在本机已按步骤 3 配置并启动过。**步骤 1 的数据库导入是本流程中
> 唯一未实测的关键环节**——请在首次部署时优先验证它（导入后可用 `SHOW TABLES` 核对表数；表数有三个口径，见 [1. 初始化数据库](#1-初始化数据库) 的说明）。

### 0. 前置依赖

| 依赖 | 版本要求 | 校验命令 | 说明 |
|------|---------|---------|------|
| JDK | **1.8**（Java 8） | `java -version` | Spring Boot 2.7 + `<java.version>1.8</java.version>`，**不要用 JDK 11+** |
| Maven | 3.8+ | `mvn -v` | 仅用于构建/启动后端；若报 classworlds 错误见 FAQ Q2 |
| MySQL | **8.x** | `mysql --version` | 需支持 `utf8mb4`；本地或远程实例均可 |
| Redis | 6+ | `redis-cli ping` → `PONG` | 限流、IP 封禁、Nonce 防重放、权限缓存 |
| Node.js | 16 / 18 LTS（20/22 亦可） | `node -v` | 需与 Vue CLI 5 兼容 |
| npm | 8+ | `npm -v` | 随 Node 安装 |

> **一线实测环境**（下述版本号均为实际执行版本命令所得）：JDK `1.8.0_391`、`Apache Maven 3.8.8`、
> Node `22.22.2` / npm `10.9.7`；后端实际连接 MySQL 8、Redis（本机 `PING` → `+PONG`）。
> JDK 与 Maven 的版本号来自 `java -version` 与 classworlds 兜底命令的 `-v` 输出。

### 1. 初始化数据库

`init.sql` 自带建库语句（`CREATE DATABASE IF NOT EXISTS \`gatekeeper\`` + `USE \`gatekeeper\``），**无需先手动建库**，直接导入即可：

```bash
mysql -h <MySQL主机> -P <MySQL端口> -u <用户名> -p \
  < src/backend/src/main/resources/sql/init.sql
```

示例（本地默认端口）：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p < src/backend/src/main/resources/sql/init.sql
```

执行后会提示输入密码。验证导入结果（**仅 `init.sql` 时期望 34 张表**）：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p -e "USE gatekeeper; SHOW TABLES;"
```

> ⚠️ **本步骤未在本仓库开发环境中实测**（该环境未安装 `mysql` 客户端）。上面两条命令是按 MySQL 官方
> 语法与 `init.sql` 实际头部语句（`SET NAMES utf8mb4;` / `CREATE DATABASE IF NOT EXISTS` / `USE`）核对得出。
> 请首次部署时优先执行并确认表数为 **34**。可参考已验证的脚本静态事实：1417 行、34 张表、
> 全部 `CREATE TABLE IF NOT EXISTS`（幂等）、种子数据落在 9 张系统域表上。
>
> ℹ️ **表数有三个口径，别混**（2026-09-27 记；2026-09-29 实机复核）：① `init.sql` 单独导入 = **34 张**；
> ② 再叠加下面五个迁移脚本 = **40 张**；③ 走完整 Docker 初始化链（`init.sql` + `docs/sql/` 下 15 个脚本）
> = **42 张** —— 开发库与 2026-09-29 的容器实机部署实测同为此数。②与③差在 `app_quota` / `biz_line`：
> 这两张死表的建表语句已于 2026-09-27 从 `init.sql` 移除，但 `schema-v2.sql`（T01 期历史迁移脚本）
> 仍会建出它们 —— 按项目「不追改历史迁移脚本」惯例未改，仅在此说明。
>
> 🔴 **要把表数凑到完整的 40 张，还需按需执行后续迁移脚本**（都是 `CREATE TABLE IF NOT EXISTS`，可重复执行）：
> `docs/sql/t13-group-env-config.sql`（`api_group_env_config`）、
> `docs/sql/t15-1-group-encryption.sql`（`api_group_encryption_config`）、
> `docs/sql/t15-4-whitelist.sql`（`sys_ip_whitelist`）、
> `docs/sql/t16-1-encryption-master-switch.sql`（`sys_encryption_config`）、
> `docs/sql/t17-interface-crypto.sql`（`sys_interface_visibility`、`sys_interface_crypto_config`）。
>
> 🧹 **清理类脚本**（做「减法」，须在**所有**种子脚本之后执行；`init.sql` 已同步移除对应种子，用于修复已建库）：
> `docs/sql/t15-remove-bizline.sql`（下线业务线功能，表保留）、
> `docs/sql/t20-remove-dead-perms.sql`（移除 8 个无引用权限点 + 39 条角色授权）、
> `docs/sql/t19-config-wiring.sql` §3（说明 13 项无读取点 `sys_config` 的移除口径，默认不动存量库）。
>
> 📉 **2026-09-27 死代码清理**（`init.sql` / `migrate-v2.sql` 已生效；存量库一律按"不动"口径处理）：
> ① 移除 `app_quota` / `biz_line` 两张死表（全仓 0 个 Java/前端引用）的建表语句；
> ② 移除 13 项自始至终无读取点的 `sys_config`（`sys_config` 由 19 行收敛为 6 行）；
> ③ 移除 49 个无调用端点、24 个前端 API 函数、14 个孤儿 Service 方法、8 个孤儿权限点 + 39 条角色授权。
>
> ⚠️ **切勿把 `init.sql` 直接导入已存在数据的库**：脚本第 25–26 行是 `CREATE DATABASE IF NOT EXISTS \`gatekeeper\``
> + `USE \`gatekeeper\``，会**指向 `gatekeeper` 库本身**。若你想在别处试用，请先做文本替换改成临时库名，
> 或直接在全新的 MySQL 实例上执行。

> **前置条件**：`init.sql` 中的 `CREATE DATABASE` 需要该账号具备建库权限。
> 若你的运维规范不允许应用账号建库，请由 DBA 预先执行 `CREATE DATABASE gatekeeper DEFAULT CHARACTER SET utf8mb4;`，
> 再单独导入其余建表语句。

### 2. 启动 Redis

```bash
# Linux
sudo systemctl start redis-server      # 或在 /etc/redis/redis.conf 中设置 requirepass 后启动

# macOS（Homebrew）
brew services start redis              # 同样需在 redis.conf 里设置 requirepass

# Windows（解压版，路径按实际调整）
C:\redis\redis-server.exe --requirepass "GkRedis#9fQ2mL7pX!4sT8nB"

# Docker（任何平台通用）
docker run -d --name gatekeeper-redis -p 6379:6379 \
  redis:7-alpine redis-server --requirepass "GkRedis#9fQ2mL7pX!4sT8nB"
```

> 🔴 **Redis 必须设置访问密码**（`--requirepass`），且必须与后端的 `GATEKEEPER_REDIS_PASSWORD` 一致。
> 默认口令 `GkRedis#9fQ2mL7pX!4sT8nB` 见[「默认账号与密钥」](#默认账号与密钥)。
> 一个无口令的 Redis 若暴露在网络中，等于把限流计数、封禁名单与权限缓存完全对外敞开。

校验：

```bash
redis-cli -a 'GkRedis#9fQ2mL7pX!4sT8nB' ping     # 期望输出：PONG
redis-cli ping                                  # 期望输出：NOAUTH Authentication required.
```

> 若 Redis 使用非默认地址/端口，请在第 3 步配置 `GATEKEEPER_REDIS_HOST` / `GATEKEEPER_REDIS_PORT`。
> Redis 不可用时后端**仍能启动**，网关防护组件会按 fail-open 策略降级放行（详见 FAQ Q5）。
> ⚠️ **口令不一致的表现与「Redis 没启动」完全相同**（后端连不上 → 走 fail-open 降级），
> 排查时请先核对两侧口令是否一致。

### 3. 配置后端

仓库**不包含** `application.yml`（含真实密钥，已被 `.gitignore` 忽略）。请从模板复制后填写：

```bash
cd src/backend/src/main/resources
cp application.example.yml application.yml
```

然后编辑 `application.yml`，**必须**填写以下三项，否则启动自检会拒绝启动：

| 配置项 | 环境变量 | 要求 | 说明 |
|--------|---------|------|------|
| 数据库密码 | `GATEKEEPER_DB_PASSWORD` | 非空、非弱口令 | 禁止 `root` / `123456` / `admin` / `password` 等 |
| JWT 签名密钥 | `GATEKEEPER_JWT_SECRET` | **≥ 32 位**随机串 | 泄露 = 任何人可伪造管理员令牌 |
| AES 加密密钥 | `GATEKEEPER_AES_KEY` | **≥ 32 位**随机串 | 加密落库的 AppSecret；**一旦轮换历史数据将无法解密** |

> 另有一项 `GATEKEEPER_REDIS_PASSWORD`（Redis 访问口令）**已内置默认值**，本地可不填；
> 但必须与 Redis 服务 `--requirepass` 的口令一致，否则后端连不上 Redis。取值见[「默认账号与密钥」](#默认账号与密钥)。

生成随机密钥：

```bash
openssl rand -base64 32        # Linux / macOS / Git Bash
```

```powershell
# Windows PowerShell（无 openssl 时）
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
```

同时按需修改数据库连接（模板默认 `localhost:3306`，账号 `root`，库名 `gatekeeper`）：

```yaml
url: jdbc:mysql://${GATEKEEPER_DB_HOST:localhost}:${GATEKEEPER_DB_PORT:3306}/${GATEKEEPER_DB_NAME:gatekeeper}?...
```

> **推荐做法**：保持模板中的占位为空，改用环境变量注入（更利于容器化与密钥轮换，变量名与 `.env.example` / `docker-compose.yml` 完全一致）：
> ```bash
> export GATEKEEPER_DB_PASSWORD='<你的数据库密码>'
> export GATEKEEPER_JWT_SECRET="$(openssl rand -base64 32)"
> export GATEKEEPER_AES_KEY="$(openssl rand -base64 32)"
> ```
> 这种方式下 `application.yml` 里可以一个真实密钥都不出现。

### 4. 启动后端

```bash
cd src/backend
mvn spring-boot:run
```

**Windows / 部分 shell 下的 `mvn` 兜底写法**（本仓库实测必需，原因见 FAQ Q2）：

```bash
cd src/backend
java -classpath "<MAVEN_HOME>\boot\plexus-classworlds-2.6.0.jar" \
  -Dclassworlds.conf="<MAVEN_HOME>\bin\m2.conf" \
  -Dmaven.home="<MAVEN_HOME>" \
  -Dmaven.multiModuleProjectDirectory="<项目绝对路径>/src/backend" \
  org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run
```

将 `<MAVEN_HOME>` 换成 Maven 安装目录、`<项目绝对路径>` 换成本仓库根目录的绝对路径。例如：

```
<MAVEN_HOME>     → C:\apache-maven-3.8.8        （Windows，按你的实际安装位置替换）
<项目绝对路径>   → C:\projects\GateKeeper       （仓库根的绝对路径）
```

> 校验该写法是否可用：把最后的 `spring-boot:run` 换成 `-v`，能打印出 Maven 版本号即正常。

> ⚠️ `spring-boot:run` **不能加 `-o`（离线模式）**，否则报 `NoPluginFoundForPrefixException`。详见 FAQ Q3。

**打包后运行**（生产方式）：

```bash
cd src/backend
mvn clean package -DskipTests
java -jar target/gatekeeper.jar
```

**启动成功标志**（典型输出，`x.xxx` 为实际耗时）：

```
Security startup check passed: jwt/aes/db secrets are properly configured
... Tomcat started on port(s): 8080 (http) with context path '/api'
... Started GatekeeperApplication in x.xxx seconds
```

后端监听 **8080**，全局路径前缀 **`/api`**。

> 说明：第一行来自源码 `config/SecurityStartupCheck` 的日志语句（本仓库已按该实现核对）；
> 后两行为 Spring Boot 2.7 的标准启动日志。**本次未捕获一份完整的后端启动日志原文**
> （验证时的后端实例先于本次工作已在运行），因此这里给出的是"应看到的标志"，
> 判断是否启动成功请以此三行为准。

### 5. 启动前端

```bash
cd src/frontend
npm install        # 首次执行，或 package.json 变更后
npm run serve
```

**启动成功标志**（典型输出）：

```
App running at:
- Local:   http://localhost:8081/
```

> 说明：本次未捕获 dev server 的控制台原文（验证时前端已在运行），判断依据是
> `curl -s -o /dev/null -w "%{http_code}" http://localhost:8081` **返回 200**，以及端口 8081 处于监听状态。

前端 dev server 端口 **8081**，`vue.config.js` 中已将 `/api` 代理到 `http://localhost:8080`，因此前端代码只需请求 `/api/xxx`（见 `src/frontend/src/api/index.js` 的 `baseURL: '/api'`），无需处理跨域。

**生产构建**：

```bash
cd src/frontend
npm run build                    # 产物输出到 dist/
npm run build -- --no-clean      # 若构建工具因清空 dist/ 被沙箱/权限拦截，用此写法跳过清理
```

构建产物为纯静态文件，交给任意 Web 服务器托管，并把 `/api` 反代到后端 `8080`（可直接参考 `src/frontend/nginx.conf`）。

### 6. 访问与登录

浏览器打开 **http://localhost:8081**

| 账号 | 密码 |
|------|------|
| `admin` | `admin123` |

> ⚠️ 该账号为初始化种子数据，**仅用于本地开发**。部署到任何可被外部访问的环境前，
> 必须修改管理员密码，并替换第 3 步中的全部密钥。
>
> 登录页**不再预填**账号密码，需手工输入。默认账号与**全部默认密钥**的完整清单见
> 下文[「默认账号与密钥」](#默认账号与密钥)。

### 7. 验证是否跑通

**① 后端存活 + 登录（注意后端 context-path 是 `/api`）**

```bash
curl -i http://localhost:8080/api/auth/login \
  -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

期望：HTTP 200，响应体形如

```json
{"code":200,"message":"success","data":{"user":{"id":1,"username":"admin","realName":"系统管理员"},
 "permCount":86,"perms":["api:list","app:list","..."],
 "token":"eyJhbGciOiJIUzI1NiJ9..."}}
```

**② 带 token 调用受保护接口**

```bash
TOKEN=$(curl -s http://localhost:8080/api/auth/login \
  -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

curl -s "http://localhost:8080/api/app/list?page=1&size=10" \
  -H "Authorization: Bearer $TOKEN"
```

期望：`{"code":200,"message":"success","data":{"records":[...],"total":N,...}}`

**③ 前端 dev server 存活**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081
# 期望：200
```

**④ 接口文档（Knife4j）**

浏览器打开 **http://localhost:8080/api/doc.html**

---

**本节四项的实测结果**（本仓库开发环境，验证时后端/前端/Redis 均已运行）：

| 检查项 | 实测输出 |
|--------|---------|
| ① 登录 | HTTP **200**；`code=200`、`message=success`、`user={'id':1,'username':'admin','realName':'系统管理员'}`、`permCount=86`、`token` 长 141 |
| ② 带 token 调 `/api/app/list` | `{"code":200,"message":"success","data":{"records":[...],"total":1,"current":1,"size":2}}` |
| ③ 前端 `:8081` | HTTP **200** |
| ④ `/api/doc.html` | HTTP **200** |

---

## 一键部署（Docker Compose）

> ✅ **已实测**（2026-09-29）：本节步骤已在 **CentOS 7.9 + Docker 24.0.7 + Compose v5.5.1**
> 的实机上从零跑通，并通过仓库自带的 E2E 剧本 `docs/sql/t09-docker-e2e.sh`（U0–U7 全绿）。
> 实测环境、耗时、以及**首跑暴露并已修复的 3 个缺陷**见本章末尾 §6「实测记录」。
> 仍请以你本机的 Docker 版本为准。
>
> ⚠️ **最容易卡住的一步是镜像拉取**（国内直连 Docker Hub 通常不通）——先看 §0 前置条件的第 3 条。

**多实例 / 集群部署限制（未验证）**

> 本项目**仅在单实例下验证**。仓库自带的 Docker E2E 剧本 `docs/sql/t09-docker-e2e.sh`（U0–U2b–U7）
> 同样是**单实例**编排——U0 起栈为「4 业务容器 + 1 mock-upstream」，不含任何副本或横向扩容；
> 且该剧本目前**仍是静态草稿，尚未在真实 Docker 环境执行过**。
> **本文档未做过多实例 / 集群部署测试，也不构成对集群部署的支持声明。**

后端存在 **6 个 `@Scheduled` 定时任务，且没有任何分布式互斥机制**：

| 任务 | 触发方式 |
|------|---------|
| `job/AlarmEvaluateJob`（实时评估） | `fixedDelay = 10000` |
| `job/AlarmEvaluateJob`（离线评估） | `cron = "0 */5 * * * ?"` |
| `job/GrantExpireJob` | `cron = "0 0 2 * * ?"` |
| `job/LogRetentionJob` | `cron = "0 30 2 * * ?"` |
| `job/QuotaResetJob` | `cron = "0 0 0 * * ?"` |
| `job/RedisHealthMonitor` | `fixedDelay = 30000` |

「无分布式互斥」的判定依据（以下两条检索均为**空命中**）：

```bash
grep -iE "shedlock|quartz|redisson|curator|zookeeper" src/backend/pom.xml            # → 0 命中
grep -rnE "lock|Lock|mutex" src/backend/src/main/java/com/gatekeeper/job/             # → 0 命中
```

代码中 `setIfAbsent` 的用途**均与任务调度无关**，只服务于防重放与告警去重：
`AppAuthHandler.java:181`（Nonce 防重放）、`HighFrequencyDetector.java:63` 与 `OffHoursDetector.java:56`（同一时间窗内告警只发一次）。

**后果**：若以多个后端副本部署（集群，或 `docker compose up -d --scale backend=N`），这 6 个任务会在
**每个副本各执行一遍**。其中：

- `GrantExpireJob`（授权过期处理）
- `LogRetentionJob`（调用日志 `DELETE` 清理）
- `QuotaResetJob`（配额重置）

并发重复执行会导致**状态错乱或重复动作**——它们**不是**"多跑几次也无妨"的幂等任务。

**如需多实例部署，必须由部署方自行补齐以下之一（本项目未内置）：**

1. 引入 **ShedLock** 一类的分布式调度锁（`@SchedulerLock`），保证同一时刻只有一个副本真正执行；
2. 将定时调度**移出应用**，交由外部单点承担（独立调度服务、K8s CronJob 等）；
3. 若某些副本不需要后台任务（例如纯转发副本），可为它们关闭调度——但当前 `@EnableScheduling`
   与各任务类都是无条件启用的，**关闭需改动代码或自行增加配置开关**。

不想装 JDK/MySQL/Redis 时，可用仓库自带的编排一键拉起全栈（MySQL + Redis + 后端 + 前端 Nginx + mock 上游）。

### 0. 前置条件

| 项 | 要求 | 实测值 |
|---|---|---|
| Docker Engine | ≥ 20.10（需支持 `depends_on.condition`） | 24.0.7 |
| Compose | `docker compose`（V2 插件）**或**独立 `docker-compose` 二进制均可 | v5.5.1（独立二进制，本机无 V2 插件） |
| Python 3 | 仅跑 E2E 剧本时需要（脚本内含 f-string） | 3.6.8 |
| 磁盘 | ≥ 10 GB（镜像约 1.5 GB，Maven 依赖缓存另需数百 MB） | 50 GB 可用 |
| 内存 | ≥ 4 GB | 7.8 GB |

**3 个易卡点，先确认再往下走：**

```bash
# ① 能否拉取镜像（国内直连 Docker Hub 一般不通）
docker pull nginx:1.25-alpine

# ② 能否访问 Maven Central 与 npm 源（backend/frontend 镜像要在线装依赖）
curl -s -o /dev/null -w '%{http_code}\n' https://repo.maven.apache.org/maven2/   # 期望 200
curl -s -o /dev/null -w '%{http_code}\n' https://registry.npmmirror.com/        # 期望 200

# ③ 是否有 python3（缺了 E2E 剧本会在 U4 报语法错）
python3 -V
```

① 不通 ⇒ 配镜像加速，见 §5 FAQ-1；② 不通 ⇒ 改用预构建镜像，见「Docker 镜像」章；③ 缺失 ⇒ `yum install -y python3` 或 `apt install -y python3`。

### 1. 获取源码

```bash
git clone --depth 1 --branch v1.0.2 https://github.com/Change-Only/GateKeeper.git
cd GateKeeper
```

### 2. 生成 .env

```bash
cp .env.example .env
# 三项必填：GATEKEEPER_JWT_SECRET / GATEKEEPER_AES_KEY / GATEKEEPER_DB_PASSWORD（均 ≥32 位）
# 下面用 hex 而非 base64 —— 不含 / + = 等特殊字符，省掉 shell 与 dotenv 的转义麻烦
# 用 \1 反向引用保留键名 —— 不在 sed 表达式里重复写 `VAR=值` 形态，
# 否则本文档自身会被下面「提交前自检」的密钥闸门误报
sed -i "s|^\(GATEKEEPER_JWT_SECRET=\).*|\1$(openssl rand -hex 32)|"     .env
sed -i "s|^\(GATEKEEPER_AES_KEY=\).*|\1$(openssl rand -hex 32)|"         .env
sed -i "s|^\(GATEKEEPER_DB_PASSWORD=\).*|\1$(openssl rand -hex 16)|"    .env
sed -i "s|^\(GATEKEEPER_REDIS_PASSWORD=\).*|\1$(openssl rand -hex 16)|" .env
# 浏览器访问地址不是 localhost 时同步改跨域来源（同源反代下非必需，但建议设成实际值）
sed -i "s|^\(GATEKEEPER_CORS_ORIGINS=\).*|\1http://<你的IP>:8081|"      .env
chmod 600 .env
```

### 3. 起栈

```bash
docker compose up -d --build          # 无 V2 插件时用：docker-compose up -d --build
```

首次构建需 **30~40 分钟**（瓶颈是 Maven 下载依赖，不是 CPU/磁盘）；之后仅改前后端源码重建约 **1~2 分钟**（Docker 层缓存命中）。
`up -d` 本身约 40 秒 —— 其间会等 mysql/redis 初始化，再由 healthcheck 确认 backend 就绪后才启动 frontend。

### 4. 验证

```bash
docker compose ps
# 期望：mysql / redis / backend 均为 healthy，frontend 映射 0.0.0.0:8081->80

curl -s -o /dev/null -w '%{http_code}\n' localhost:8081/api/doc.html    # 期望 200
```

浏览器打开 `http://<服务器IP>:8081`，用 `admin / admin123` 登录（**首次登录后请立即改密**）。

再跑一遍仓库自带的端到端验收剧本（U0–U7：起栈 → 健康 → 初始化链落库 → 登录 → 应用 CRUD → 审计日志 → 异步导出 → 清栈）：

```bash
bash docs/sql/t09-docker-e2e.sh
```

> 该剧本用的是 `docker compose`（V2 插件语法）。若你的机器只有独立二进制，
> 用一个 PATH 垫片即可兼容，见 §5 FAQ-2。

容器启动时由 `docker-entrypoint-initdb.d/` 下的 SQL 链自动完成建库、建表与种子数据初始化，无需手动导入。

> **初始化链 = 16 个脚本，按文件名顺序执行**（`init.sql` + `docs/sql/` 下 15 个历史脚本），
> 建出与开发库逐表一致的 **42 张表**、`sys_menu` **121** 行。完整清单见 `docker-compose.yml`
> 中 `mysql.volumes` 的挂载项。
>
> 其中 `init.sql` 现已**自足**（36 张表 + 系统域种子数据），其余脚本按用途分两类：
> ① seed 类（`t02` / `t03a` / `t03b` / `t05` / `t07a` / `t08` 等）用 `INSERT IGNORE`（只补不覆盖），幂等；
> ② 结构/迁移类（`schema-v2` / `migrate-v2` / `t09-hygiene` / `t13` / `t15-1` / `t15-4` / `t16-1` / `t17`）
> 含 `CREATE TABLE IF NOT EXISTS` / `ALTER` / `UPDATE` / `DELETE`，**并非纯 no-op**，属"历史回放"性质。
>
> ⚠️ `docker-entrypoint.sh` 在任一脚本报错时**会中止整条链**（后续脚本全部不执行，容器随后
> 以"已有数据"重启并跳过 initdb）。因此**表数与菜单数是最灵敏的健康指标** —— 部署后请核对
> §4 的两条断言（42 张表、`sys_menu` = 121）。
>
> 想简化也可只用 `init.sql` 单独导入（见上一节本地启动步骤 1）——它已能独立构建出 36 张表与
> 基础种子数据，但会**缺 6 张表**（白名单、分组加解密、接口级加解密相关），对应功能会抛
> SQL 异常。**生产部署请走完整链。**

### 5. 常见问题

**FAQ-1 · `docker pull` 卡住 / `registry-1.docker.io` 超时**

国内直连 Docker Hub 通常不通。改 `/etc/docker/daemon.json`（**先备份**）：

```bash
cp -a /etc/docker/daemon.json /etc/docker/daemon.json.bak
cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://docker.m.daocloud.io", "https://docker.1ms.run"]
}
EOF
systemctl restart docker
docker pull nginx:1.25-alpine        # 验证
```

> 实测提醒：多数老教程里的加速器（`registry.docker-cn.com`、`docker.mirrors.ustc.edu.cn`、
> `hub-mirror.c.163.com`、`mirror.ccs.tencentyun.com` 等）**均已失效**，配了反而更慢或直接超时。
> 若加速器也不可用，可改用 `public.ecr.aws/docker/library/<image>`（AWS 公共镜像库，与 Docker
> 官方镜像同源），或直接用 Release 里的离线镜像包（见「Docker 镜像」§5）。

**FAQ-2 · 机器上只有 `docker-compose`，没有 `docker compose`**

仓库自带的 E2E 剧本用的是 V2 插件语法。放一个 PATH 垫片即可让两种写法都工作：

```bash
mkdir -p /opt/gk-tools && cat > /opt/gk-tools/docker <<'EOF'
#!/bin/sh
if [ "$1" = "compose" ]; then shift; exec /usr/local/bin/docker-compose "$@"; fi
exec /usr/bin/docker "$@"
EOF
chmod +x /opt/gk-tools/docker
PATH=/opt/gk-tools:$PATH bash docs/sql/t09-docker-e2e.sh
```

**FAQ-3 · 起栈后访问 8081 得到 502**

backend 尚未就绪（Spring Boot 启动约 8~15 秒）。编排已给 backend 配 healthcheck 且 frontend
`depends_on` 它 —— `docker compose ps` 中 backend 显示 `(healthy)` 后再访问即可。
若持续 502，查 `docker compose logs backend`。

**FAQ-4 · E2E 剧本在 U4 报 `SyntaxError: invalid syntax`**

系统只有 Python 2（脚本用了 f-string）。装 Python 3：`yum install -y python3` 或 `apt install -y python3`。

**FAQ-5 · 改了 `init.sql` / 加了脚本，重建后却不生效**

`docker-entrypoint-initdb.d` **仅在数据卷为空时**执行。必须 `docker compose down -v` 再 `up`，
否则会跳过初始化、继续用旧数据。

### 6. 实测记录（2026-09-29）

**环境**

| 项 | 值 |
|---|---|
| 主机 | CentOS Linux 7 (Core)，内核 3.10.0-693.el7.x86_64，4 核 / 7.8 GB / 50 GB 可用 |
| Docker | 24.0.7（daemon active）；Compose **v5.5.1**（独立二进制，**无 `docker compose` V2 插件**） |
| 其它 | SELinux `Enforcing`；firewalld `inactive`；系统自带 Python 2.7.5（另装 3.6.8） |
| 部署方式 | `git clone --depth 1 --branch v1.0.2` → `.env` → `docker-compose up -d --build` |

**耗时**

| 阶段 | 耗时 |
|---|---|
| `docker-compose build`（首次，含 Maven 全量依赖下载） | **33 分 32 秒**（`mvn dependency:go-offline` 占约 31 分钟，瓶颈在网络而非 CPU） |
| 仅改后端源码后重建（Docker 层缓存命中） | 1 分 33 秒 |
| `up -d`（到全栈 healthy，含 mysql 初始化） | 约 40 秒 |
| E2E 剧本 U0–U7 全程 | 约 3 秒（镜像与基础卷已就绪时） |

**结果**：`gatekeeper-backend:latest` 322 MB、`gatekeeper-frontend:latest` 50.6 MB；
E2E **U0–U7 全绿**；部署态自检 **23/23 通过**；表集合与开发库 **42/42 逐表一致**。

**首跑暴露并已修复的 3 个缺陷**

| # | 缺陷 | 症状 | 修复 |
|---|---|---|---|
| 1 | `init.sql` 第 1393 行 `sys_config` 种子的最后一个 VALUES 元组**多一个逗号** | initdb 报 `ERROR 1064` 后中止 ⇒ 后续 10 个脚本全未执行，落库只剩 **34 表 / 99 菜单** | 删除该逗号（提交 `a5979e3`） |
| 2 | `docker-compose.yml` 挂载清单**漏挂 5 个结构脚本**（`t13`/`t15-1`/`t15-4`/`t16-1`/`t17`） | 只有 **36 张表**，缺 `api_group_env_config`、`api_group_encryption_config`、`sys_encryption_config`、`sys_interface_crypto_config`、`sys_interface_visibility`、`sys_ip_whitelist` ⇒ 白名单 / 分组加解密 / 接口级加解密**直接抛 SQL 异常**；源码里 `@RequirePerm("api_group_env_config:create")` 等 **9 个权限点连超管都拿不到** | 补挂 5 个脚本（链 11 → 16 文件） |
| 3 | 起栈后立刻探活得到 **502** | compose 只保证 mysql/redis healthy 后【启动】backend，Spring Boot 自身还要 8~15 秒；E2E 的 U2 未等待 ⇒ 首次运行误报失败 | backend 加 healthcheck（`/dev/tcp` 探测）+ frontend `depends_on: service_healthy`；E2E 的 U2 增加就绪等待循环 |

> 缺陷 1、2 的共同根因：这条初始化链**此前从未在真实 Docker 环境执行过**（旧版本节对此有明确声明）。
> 它们不会在单元测试里暴露 —— 只有真正 `up` 起来才看得见。
> 缺陷 2 尤其隐蔽：应用能正常启动、登录也正常，**只有点进相关功能页才会报错**。

**部署后建议的自检**

```bash
DQ() { docker exec -e SQL="$1" gatekeeper-mysql \
  sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -D "$MYSQL_DATABASE" -e "$SQL"' 2>/dev/null; }
DQ "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"   # 期望 42
DQ "SELECT COUNT(*) FROM sys_menu"                                                  # 期望 121
DQ "SELECT COUNT(*) FROM sys_menu WHERE id=221"                                     # 期望 0
```

**未实测的部分（不要当成已验证）**：多实例 / 集群部署（见本章开头的限制说明）、跨主机部署、
TLS / 域名接入、备份恢复流程。

---

## Docker 镜像

前后端均已容器化，两个 Dockerfile 都是多阶段构建：`src/backend/Dockerfile`（Maven 编译 → JRE 运行）、
`src/frontend/Dockerfile`（Node 构建 SPA → Nginx 托管并反代 `/api` 到后端容器）。

### 1. 一键构建 / 推送 / 导出

```bash
bash docker/build-and-push.sh              # 只构建并打标签
bash docker/build-and-push.sh --push       # 构建后推送到 Docker Hub
bash docker/build-and-push.sh --push --save   # 再额外导出镜像 tar 包到 dist/docker/
```

Windows PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File docker\build-and-push.ps1 -Push
```

环境变量可覆盖：`GK_DOCKER_NAMESPACE`（Docker Hub 命名空间，默认 `changeonly`）、
`GK_VERSION`（默认从 `src/backend/pom.xml` 自动读取，并会与前端 `package.json` 比对，不一致直接中止）。

### 2. 镜像标签（单仓库多 tag）

| 镜像 | 标签 |
| --- | --- |
| 后端网关 | `<namespace>/gatekeeper:backend-<version>` |
| 前端管理后台 | `<namespace>/gatekeeper:frontend-<version>` |

版本号同时写进镜像的 OCI 标签 `org.opencontainers.image.version`，可独立于 tag 读取：

```bash
docker inspect <namespace>/gatekeeper:backend-1.0.2 \
  --format '{{index .Config.Labels "org.opencontainers.image.version"}}'
```

### 3. 直接运行

```bash
docker run -d --name gk-backend -p 8080:8080 \
  -e GATEKEEPER_DB_HOST=<数据库主机> -e GATEKEEPER_DB_PASSWORD=<数据库口令> \
  -e GATEKEEPER_REDIS_HOST=<Redis主机> -e GATEKEEPER_REDIS_PASSWORD=<Redis口令> \
  -e GATEKEEPER_JWT_SECRET=<32位以上随机串> -e GATEKEEPER_AES_KEY=<32位以上随机串> \
  <namespace>/gatekeeper:backend-1.0.2

docker run -d --name gk-frontend -p 8081:80 <namespace>/gatekeeper:frontend-1.0.2
```

更省事的方式是直接用编排（见上一节）：`docker compose up -d`。

### 4. 镜像内不含真实密钥（重要）

`src/main/resources/application.yml` 是本地真实配置（含真实密钥与内网库地址），
它**未入库但确实存在于本地**。`.gitignore` 只管 git，**管不住 `docker build` 的构建上下文**——
所以 `src/backend/.dockerignore` 显式排除了它。构建时改由官方模板顶替：

```dockerfile
RUN cp src/main/resources/application.example.yml src/main/resources/application.yml && mvn -B package -DskipTests
```

模板与本地配置**键集完全相同**（各 62 个键，已逐键比对），差异仅在 5 处：
三项密钥的默认值为空、库地址默认 `localhost:3306`、库账号默认 `root`。

因此镜像的安全基调是 **fail-fast**：不注入 `GATEKEEPER_JWT_SECRET` / `GATEKEEPER_AES_KEY` /
`GATEKEEPER_DB_PASSWORD` 时，`SecurityStartupCheck` 会让进程**直接拒绝启动**，而不是以弱默认值裸奔。

> 构建脚本已内置前置校验：若 `.dockerignore` 漏排 `application.yml`、或 Dockerfile 少了模板顶替那一步，
> 脚本会**直接中止构建**，避免密钥被烤进镜像推到公开仓库。

#### 4.1 同一条闸门也适用于 Release 的 jar 资产（易漏）

`gatekeeper-backend-<version>.jar` **不在 Dockerfile 流程内**，必须手工按同一口径打包，
否则会把本地真实 `application.yml`（**内网库地址 + 真实密钥**）直接烤进发布物：

```bash
cd src/backend
cp src/main/resources/application.yml /path/to/application.yml.real   # 先备份本地真实配置
cp src/main/resources/application.example.yml src/main/resources/application.yml
mvn package -DskipTests
cp /path/to/application.yml.real src/main/resources/application.yml   # 打完立刻还原
```

**判据**：解包 jar 后 `BOOT-INF/classes/application.yml` 中**不得**出现真实内网库主机，
库地址应回落到 `localhost`：

```bash
unzip -p target/gatekeeper.jar BOOT-INF/classes/application.yml | grep -E "url: jdbc|192\.168\."
# 期望：只看到 ${GATEKEEPER_DB_HOST:localhost}，看不到任何内网 IP
```

> 这条曾经真的踩过：直接用仓库工作区跑 `mvn package` 得到的 jar 里，
> 库地址是内网实机、密钥是真实值 —— 而镜像包因为走 Dockerfile 反而是干净的，
> **同一个版本出现「镜像干净、jar 泄漏」的不一致**。发布前请对 jar 单独复检。

### 5. 从 Release 资产加载离线镜像

每个版本的 Release 都附带两个 `docker load` 可直接加载的镜像包（含基础镜像层，离线可用）：

```bash
docker load -i gatekeeper-backend-image-1.0.2.tar
docker load -i gatekeeper-frontend-image-1.0.2.tar
# 加载后即为 <namespace>/gatekeeper:backend-1.0.2 / :frontend-1.0.2
```

包内是标准 `docker save` 格式（`manifest.json` + gzip 层），平台为 `linux/amd64`。
资产文件名中的版本号即当前 Release 版本，换版本时同步替换即可。

Release 资产清单（与 `v1.0.2` 一一对应）：

| 资产 | 内容 |
| --- | --- |
| `gatekeeper-backend-1.0.2.jar` | 后端可执行 fat jar |
| `gatekeeper-frontend-1.0.2.zip` | 前端生产构建产物（静态文件） |
| `gatekeeper-sql-1.0.2.zip` | 数据库脚本（建表 / 迁移 / 权限播种） |
| `gatekeeper-backend-image-1.0.2.tar` | 后端镜像（含基础层，离线可加载） |
| `gatekeeper-frontend-image-1.0.2.tar` | 前端镜像（含基础层，离线可加载） |
| `gatekeeper-docker-1.0.2.zip` | Dockerfile、`.dockerignore`、`nginx.conf`、构建脚本与镜像元数据 |
| `gatekeeper-1.0.2-SHA256SUMS.txt` | 三类源码资产的校验和 |
| `gatekeeper-docker-1.0.2-SHA256SUMS.txt` | 三项 Docker 资产的校验和 |

离线包与 Release 一一对应，**同版本号的资产内容与源码提交一一对应**：
镜像内 `org.opencontainers.image.revision` 标签即该版本对应的 git 提交，
`docker inspect` 可直接读出（见上一节），无需依赖 tag 是否被移动。

---

## 默认账号与密钥

> ⚠️ **本节内容会随公开仓库一并发布。** 下列取值全部是**本地开发 / 演示环境的默认值**，
> 一旦用于任何可被外部访问的环境即等同无效，请一律视为**已泄露**。部署前必须逐项替换。
>
> 诚实说明：其中 AES 密钥的历史副本本就存在于本仓库的公开提交历史里；
> 而**数据库口令与 JWT 密钥此前从未入库，是本节首次将其公开**——请据此评估风险。

### 1. 默认账号

| 账号 | 密码 | 来源 |
|------|------|------|
| `admin` | `admin123` | 初始化种子数据（`docs/sql/t02-seed-admin-role.sql`） |
| *（新建用户）* | `123456` | 通过「用户管理」新建用户、且未填密码时的初始口令兜底（`SysUserServiceImpl#createUser`） |

登录页已**不再预填**任何账号密码（`src/frontend/src/views/system/Login.vue`），需手工输入。

### 2. 全部默认密钥

| 环境变量 | 对应配置项 | 默认值 | 泄露后果 |
|---------|-----------|--------|---------|
| `GATEKEEPER_DB_PASSWORD` | `spring.datasource.password` | `GK#Db9f3!xQ7-Lm2pR8vZ` | 可直接连库读写全部业务数据 |
| `GATEKEEPER_AES_KEY` | `gatekeeper.crypto.aes-key` | `Gk9#aQ2!vL7pX4sT8nB3mZ6cR1yF5wJ0` | 落库密钥的加密密钥（KEK）：可解密 `app_secret`、加密存储的接口路径与参数等全部 `enc:` 密文 |
| `GATEKEEPER_JWT_SECRET` | `gatekeeper.jwt.secret` | `GkJwt#7dKq2!vX9pL4sT8nB3mZ6cR1yF5wJ0eA2hD` | 可用自签令牌冒充任意用户（含管理员），直接接管管理后台 |
| `GATEKEEPER_REDIS_PASSWORD` | `spring.redis.password` | `GkRedis#9fQ2mL7pX!4sT8nB` | 可读写限流计数 / 封禁名单 / 权限缓存，绕过或误伤网关防护 |

> 其余**非密钥类**默认值（DB 主机与端口、库名、CORS 来源、导出目录、环境码等）见下方「配置项说明」。

### 3. 本地开发怎么用（零配置）

`application.yml` 与 `docker-compose.yml` 均内置上述默认值，本地开箱即用：

```bash
# ① Redis 必须带同一口令启动，否则后端无法建立连接
redis-server --requirepass 'GkRedis#9fQ2mL7pX!4sT8nB'

# 校验（两种写法都行）
redis-cli -a 'GkRedis#9fQ2mL7pX!4sT8nB' ping     # 期望输出：PONG
REDISCLI_AUTH='GkRedis#9fQ2mL7pX!4sT8nB' redis-cli ping

# ② 后端、前端照常启动（详见上一节「本地启动方法」）
# ③ 打开 http://localhost:8081，用 admin / admin123 登录
```

### 4. 上线前必须替换

```bash
# 生成随机密钥（Linux / macOS / Git Bash）
export GATEKEEPER_JWT_SECRET="$(openssl rand -base64 32)"
export GATEKEEPER_AES_KEY="$(openssl rand -base64 32)"
export GATEKEEPER_DB_PASSWORD='<新的数据库强口令，≥12 位>'
export GATEKEEPER_REDIS_PASSWORD="$(openssl rand -base64 24)"
```

- 🔴 **替换 `GATEKEEPER_AES_KEY` 会使历史密文永久无法解密**，必须配套数据重加密方案，切勿随意轮换。
- 同时：修改 `admin` 默认口令、`knife4j.enable: false`、按实际域名收敛 `gatekeeper.cors.allowed-origins`。

---

## 配置项说明

所有密钥类配置均支持**环境变量覆盖**，变量名与 `.env.example`、`docker-compose.yml` 保持一致。

### 必填（启动自检强校验）

| 环境变量 | 对应配置项 | 要求 |
|---------|-----------|------|
| `GATEKEEPER_DB_PASSWORD` | `spring.datasource.password` | 非空，非弱口令 |
| `GATEKEEPER_JWT_SECRET` | `gatekeeper.jwt.secret` | ≥ 32 位 |
| `GATEKEEPER_AES_KEY` | `gatekeeper.crypto.aes-key` | ≥ 32 位 |

### 数据库与缓存

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `GATEKEEPER_DB_HOST` | `localhost` | MySQL 主机 |
| `GATEKEEPER_DB_PORT` | `3306` | MySQL 端口 |
| `GATEKEEPER_DB_NAME` | `gatekeeper` | 数据库名 |
| `GATEKEEPER_DB_USERNAME` | `root` | 数据库账号 |
| `GATEKEEPER_REDIS_HOST` | `localhost` | Redis 主机 |
| `GATEKEEPER_REDIS_PORT` | `6379` | Redis 端口 |
| `GATEKEEPER_REDIS_DATABASE` | `0` | Redis 逻辑库编号 |
| `GATEKEEPER_REDIS_PASSWORD` | `GkRedis#9fQ2mL7pX!4sT8nB` | Redis 访问密码；Redis 侧须以 `--requirepass` 启动同一口令，默认值见[「默认账号与密钥」](#默认账号与密钥) |
| `GATEKEEPER_REDIS_FAIL_OPEN` | `true` | Redis 故障时防护组件是否降级放行 |

### 运行时

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `GATEKEEPER_CORS_ORIGINS` | `http://localhost:8081` | 允许跨域来源，逗号分隔；生产改成实际域名 |
| `GATEKEEPER_EXPORT_DIR` | `./data/exports` | 调用日志异步导出 CSV 落盘目录 |
| `GATEKEEPER_ENV` | `prod` | 网关默认环境码，可被请求头 `X-Gk-Env` 逐请求覆盖 |

### 其他常用配置项（`application.yml` 内调整）

| 配置项 | 默认值 | 说明 |
|--------|-------|------|
| `server.port` | `8080` | 后端监听端口 |
| `server.servlet.context-path` | `/api` | 全局路径前缀，前端代理依赖此值 |
| `gatekeeper.jwt.expire-minutes` | `120` | 登录令牌有效期（分钟） |
| `gatekeeper.security.login-fail-threshold` | `5` | 管理后台登录失败锁定阈值 |
| `gatekeeper.security.login-lock-minutes` | `10` | 登录失败锁定时长 |
| `gatekeeper.security.trust-xff` | `false` | 是否信任 `X-Forwarded-For`（仅在可信反代后才可置 `true`） |
| `gatekeeper.security.auto-ban-duration-min` | `60` | 触发异常检测后自动封禁时长 |
| `gatekeeper.log.retention-days` | `90` | 调用日志保留天数 |
| `knife4j.enable` | `true` | 接口文档开关，生产建议置 `false` |

完整配置项及中文注释见 [`src/backend/src/main/resources/application.example.yml`](src/backend/src/main/resources/application.example.yml)。

---

## 常见问题（FAQ）

### Q1. 后端启动报「安全配置自检失败，服务拒绝启动」

```
==================== 安全配置自检失败，服务拒绝启动 ====================
  [X] 缺少 gatekeeper.jwt.secret：请设置环境变量 GATEKEEPER_JWT_SECRET
  [X] 缺少数据库密码：请设置环境变量 GATEKEEPER_DB_PASSWORD
====================================================================
```

**原因**：`com.gatekeeper.config.SecurityStartupCheck` 在 Bean 初始化阶段（早于端口监听）强校验三项密钥。
出现**缺失 / 长度 < 32 位 / 等于历史默认值或常见弱口令**（`123456`、`root`、`admin`、`password`）任一情况即 fail-fast。

**这是刻意的安全设计**：历史版本曾把 JWT/AES/DB 默认密钥写进配置文件并提交仓库，任何人拿到仓库即可伪造管理员令牌。
现在配置文件中不再携带任何真实默认值。

**解决**：按第 3 步填好三项密钥（注意 JWT 与 AES 必须 ≥ 32 位）后重启。

### Q2. `mvn` 报「找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher」

```
错误: 找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher
```

**原因**：某些 shell / 终端环境（如 Windows 下的 Git Bash、非标准安装的 Maven）下，
`mvn` 启动脚本未能正确拼装 `classworlds` 的 classpath。

**解决**：绕开 `mvn` 启动脚本，直接用 `java` 调起 Maven 的 launcher（见第 4 步）：

```bash
java -classpath "<MAVEN_HOME>\boot\plexus-classworlds-2.6.0.jar" \
  -Dclassworlds.conf="<MAVEN_HOME>\bin\m2.conf" \
  -Dmaven.home="<MAVEN_HOME>" \
  -Dmaven.multiModuleProjectDirectory="<项目绝对路径>\src\backend" \
  org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run
```

把 `<MAVEN_HOME>` 换成你的 Maven 安装目录（如 `C:\apache-maven-3.8.8`）。
校验该写法是否可用：把最后的 `spring-boot:run` 换成 `-v`，能打印 Maven 版本号即正常。

> **实测依据**：本仓库开发环境确实复现了上述报错（`mvn -v` 即失败），且该兜底写法实测有效——
> `-v` 输出 `Apache Maven 3.8.8` / `Java 1.8.0_391`；随后执行 `compile` 目标退出码为 0。
> 结论为「本机必需」；在你的机器上 `mvn` 若正常，直接用标准写法即可。

### Q3. `spring-boot:run` 报 `NoPluginFoundForPrefixException`

**原因**：`spring-boot:run` 使用插件前缀（prefix）解析，需要在**在线**状态下从远程仓库解析插件元数据。
一旦加了 `-o`（离线模式），前缀解析必然失败。

**解决**：启动后端时**不要加 `-o`**。只有 `mvn test`（其插件已缓存）才可以安全加 `-o`。

> **验证范围**：本条为**机制性结论，未逐条实测**——我们实测过的是「`mvn` 在本机需走 classworlds 兜底」（Q2），
> 以及「离线模式下插件前缀无法解析」这一 Maven 副作用。稳妥做法是：启动后端时不要加 `-o`，
> 若你的环境网络受限，可预先 `mvn dependency:go-offline` 预热本地仓库后再试。

### Q4. 前端 `npm run build` 被拦截 / `dist/` 报权限或批量删除错误

**现象**：构建前清空 `dist/` 的操作被安全策略拦截（如 `SAFE_DELETE_BULK_CONFIRM_REQUIRED`）。

**解决**：跳过清理步骤：

```bash
npm run build -- --no-clean
```

手动删除 `dist/` 后重跑 `npm run build` 亦可。

> **实测依据**：`npm run build -- --no-clean` 在本仓库开发环境实测成功，输出
> `DONE Build complete. The dist directory is ready to be deployed.`。
> 需注意该参数**不清理旧产物**，多次构建会在 `dist/` 中累积同名不同 hash 的历史文件
> （实测可见十几次构建的残留），发布前建议手动清空 `dist/` 再构建一次。

### Q5. Redis 没启动（或口令不一致），网关防护还生效吗？

**不会全部生效，但业务不中断。** 项目采用 **fail-open 降级**策略（`GATEKEEPER_REDIS_FAIL_OPEN` 默认 `true`）：

> ⚠️ **先排除「口令不一致」**：Redis 以 `--requirepass` 启动、而后端 `GATEKEEPER_REDIS_PASSWORD` 与之不符时，
> 连接会被拒绝（`NOAUTH`），**表现与本条完全相同**（走 fail-open 降级、日志只见 Redis 异常）。
> 默认口令见[「默认账号与密钥」](#默认账号与密钥)；核对两侧一致后仍异常，再按下面的降级行为排查。

- **降级放行**（Redis 不可用时自动跳过，仅记录 WARN 日志）：IP 封禁检查、频率限制、Nonce 防重放、权限缓存命中。
- **仍然生效**：应用身份校验、IP 白名单、接口权限校验等基于数据库的管控。

安全性要求极高的场景（强合规）可把 `gatekeeper.redis.fail-open` 置为 `false`，改为 **fail-closed**：
Redis 故障时防护组件直接抛错、网关整体拒绝请求，代价是 Redis 抖动会直接导致网关不可用。

> **验证范围**：本条为**代码级结论，未做"停掉 Redis"的运行时实测**。依据是源码实现——
> `gateway/handler/IpBanCheckHandler` 通过 `@Value("${gatekeeper.redis.fail-open:true}")` 读取该开关，
> 并在 Redis 异常分支按开关决定「放行」或「抛错」；`AppAuthHandler` 的 Nonce 防重放同样标注为 fail-open 降级。
>
> ⚠️ 另外提示一个配置细节：该开关的**正确配置键是 `gatekeeper.redis.fail-open`**（本仓库模板
> `application.example.yml` 已按此放置），请勿写在 `spring.redis` 下——那样不会生效。

> 生产环境请务必保证 Redis 高可用，不要依赖 fail-open 兜底。

### Q6. 前端请求报 404 / 跨域错误

- **404**：先确认后端 `context-path` 是 `/api`。前端请求路径必须是 `/api/xxx`（`src/frontend/src/api/index.js` 的 `baseURL: '/api'`），
  而 `vue.config.js` 的代理是**原样转发** `/api` 前缀到 `http://localhost:8080` 的，不会去掉前缀。
  可用 `curl` 直接打后端 `http://localhost:8080/api/xxx` 交叉验证。
- **跨域（CORS）**：走前端 dev server（8081）时由代理规避，不会跨域。
  若直接用其他端口/域名访问后端，需把该来源加入 `GATEKEEPER_CORS_ORIGINS`（逗号分隔）。

### Q7. 端口被占用

后端默认 8080、前端默认 8081、Redis 6379。

```bash
# Windows
netstat -ano | findstr ":8080"
# Linux / macOS
lsof -i :8080
```

临时换端口：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18080"
```

前端改端口需同时修改 `vue.config.js` 的 `devServer.port`（代理目标不变）。

### Q8. 数据库连不上 / 时区或编码异常

- 确认 JDBC URL 参数完整，尤其是 `useSSL=false`（本地无证书时）、`allowPublicKeyRetrieval=true`（MySQL 8 caching_sha2 认证需要）、
  `serverTimezone=Asia/Shanghai`、`characterEncoding=utf8`。
- 若 MySQL 使用**非默认端口**，务必显式设置 `GATEKEEPER_DB_PORT`，模板默认是 `3306`。
- 中文乱码：确认库与表使用 `utf8mb4`（`init.sql` 已指定 `DEFAULT CHARACTER SET utf8mb4`）。

---

## 数据库设计

初始化脚本为 [`src/backend/src/main/resources/sql/init.sql`](src/backend/src/main/resources/sql/init.sql)，**单独执行可建 34 张表**；
叠加 `t13` / `t15-1` / `t15-4` / `t16-1` / `t17` 五个迁移脚本后共 **40 张表**。
（开发库实测 42 张 —— 多出的 2 张是 `app_quota` / `biz_line`，存量库按"只加不删"不动；
完整 Docker 初始化链（16 文件）在 2026-09-29 实机部署实测同样为 **42 张**，且与开发库**表集合逐表一致**。
口径见上文「1. 初始化数据库」。）
按域划分如下：

| 域 | 表 | 说明 |
|----|-------|------|
| 环境 | `env` | 环境字典（dev/test/prod）；网关与配置按 `env_code` 隔离 |
| 接口资产 | `api_group`、`api_interface`、`api_param`、`api_version`、`api_env_config`、`api_group_env_config`、`api_change_log` | 接口注册、分组、版本、参数、环境配置（接口级 + 分组级）、变更审计 |
| 应用身份 | `app`、`app_credential`、`app_ip_whitelist`、`app_rate_limit` | 应用主体、多环境凭证、IP 白名单、限流与配额（配额即以 `app_rate_limit` 为准） |
| 授权关系 | `app_api_permission`、`app_api_grant` | 应用-接口授权；前者为存量快照（网关回退用），后者带审批流 / 有效期 / 环境 |
| 加解密 | `sys_encryption_config`、`api_encryption_config`、`api_group_encryption_config`、`app_encryption_config`、`sys_interface_crypto_config`、`sys_interface_visibility` | 平台总开关、接口/分组/应用三级加解密配置、接口信息存储加密开关与可见性白名单 |
| 调用与导出 | `api_call_log`、`export_task` | 全链路调用日志（25 列）与异步导出任务 |
| 安全防护 | `ip_ban`、`block_rule`、`security_event`、`security_rule` | 封禁名单、动态封禁规则、安全事件与检测规则 |
| 告警 | `alert`、`alarm_rule`、`notify_channel` | 告警记录、告警规则、通知渠道 |
| 系统管理 | `sys_user`、`sys_role`、`sys_user_role`、`sys_menu`、`sys_role_menu`、`sys_role_datascope`、`sys_dict`、`sys_dict_item`、`sys_config`、`sys_operation_log`、`sys_ip_whitelist` | RBAC、菜单权限点、数据权限、字典、参数、操作审计、系统级访问白名单 |
| 已下线 / 已移除 | — | **T15 下线的 `biz_line`** 与**从未被读取的 `app_quota`**：2026-09-27 起不再由 `init.sql` 建表（全仓 0 个 Java/前端引用）。`app.line_id` / `api_interface.line_id` / `sys_user.line_id` 等列按"只加列不删列"铁律物理保留，存量库中的两张表亦不动 |

全部建表语句均为 `CREATE TABLE IF NOT EXISTS`，脚本可**重复执行**（幂等）；`init.sql` 规模 **1417 行**。
种子数据仅包含**基础运行数据**，落在 **9 张系统域表**上：
`sys_user`（管理员 `admin`）、`sys_role`、`sys_menu`（全部权限点，含菜单与按钮）、`sys_user_role`、
`sys_role_menu`、`sys_role_datascope`（数据权限）、`sys_dict`、`sys_dict_item`、`sys_config`。
——**不含任何业务数据**（无应用、接口、调用日志）。
更详细的表结构说明见 [`docs/database-design.md`](docs/database-design.md)，
业务视角的表域划分与核心流程见 [`docs/GateKeeper-业务与核心流程.md`](docs/GateKeeper-业务与核心流程.md)。

> ⚠️ **`sys_config` 的生效范围**（T19，2026-09-18 实测 / 2026-09-27 收敛）：全表 **6 项**全部被代码读取并真正生效
> （`sign.algorithm`、`sign.timestamp.tolerance`、`sign.nonce.ttl`、`gateway.auth.enabled`、
> `gateway.ratelimit.enabled`、`gateway.default.read.timeout`，`remark` 中均标注「读取点 xxx」）。
> 原先另有 **13 项**"看起来能改、实际无任何读取点"的配置，已于 2026-09-27 连行移除，
> 参数配置页不再出现误导性开关（存量库清理办法见 `docs/sql/t19-config-wiring.sql` §3）。
> 新增读取点请通过 `config/SysConfigAccessor`，并在 `docs/sql/t19-config-wiring.sql` 同步备注。

---

## 文档索引

| 文档 | 内容 |
|------|------|
| [`docs/GateKeeper-业务与核心流程.md`](docs/GateKeeper-业务与核心流程.md) | **业务全景与核心流程**（定位、角色、业务域、网关链路、资产生命周期、授权状态机、安全闭环、数据模型、已知边界） |
| [`docs/PRD-APIM重新设计.md`](docs/PRD-APIM重新设计.md) | 产品需求文档（APIM 重新设计） |
| [`docs/APIM重新设计-总纲.md`](docs/APIM重新设计-总纲.md) | 总体设计总纲 |
| [`docs/架构设计-APIM重新设计.md`](docs/架构设计-APIM重新设计.md) | 系统架构设计 |
| [`docs/database-design.md`](docs/database-design.md) | 数据库设计说明 |
| [`docs/T05-权限点契约对齐方案.md`](docs/T05-权限点契约对齐方案.md) | 权限点契约对齐方案 |
| [`docs/T08-权限执行缺口-契约记录.md`](docs/T08-权限执行缺口-契约记录.md) | 服务端权限执行缺口修复记录 |
| [`docs/启动记录与缺陷修复.md`](docs/启动记录与缺陷修复.md) | 启动过程记录与缺陷修复 |
| [`docs/告警功能说明.md`](docs/告警功能说明.md) | 告警中心功能说明 |
| [`security/security-report.md`](security/security-report.md) | 安全测评报告 |
| [`design/spec.md`](design/spec.md) | UI 设计规范 |

---

## 安全说明

本项目采用**激进密钥策略**——但这说的是**模板与启动自检**：

- 被跟踪的模板文件（`application.example.yml`、`.env.example`）**不含任何可用密钥**：
  JWT / AES / 数据库口令的占位符默认值一律留空，强制部署者通过环境变量注入。
- `SecurityStartupCheck` 在 Bean 初始化阶段（**早于 Web 端口监听**）强校验 JWT / AES / 数据库口令：
  缺失、过短、命中历史默认值黑名单、**或仍等于 yml 内置默认值**（生产环境致命）——任一命中即拒绝启动。
- `application.yml`、`.env` 均已在 `.gitignore` 中忽略，**请勿提交**。

> ⚠️ **一处刻意的例外，务必知悉**：README 的[「默认账号与密钥」](#默认账号与密钥)章节
> **明文公开**了本地开发所用的默认口令与全部默认密钥（DB / Redis / AES / JWT）。
> 这是为了让本地「零配置开箱即用」而做的**知情取舍**，代价是这些值在公开仓库中
> **不再具备任何保密性**。请一律视为**已泄露**，并在任何非本地环境启动前完成替换
> （尤其 `GATEKEEPER_AES_KEY` 与 `GATEKEEPER_JWT_SECRET`）。

### 提交前自检

以下命令均**期望无输出**（`git grep` 有匹配时退出码 0，无匹配为 1）：

```bash
# ① 密钥是否被填入了真实值。判据（2026-09-29 改进，11 组用例实测通过）：
#    = 后紧跟「16 个以上、且首字符不是 . 的连续非空白串」，同时排除
#    引号 / 尖括号 / $ 开头的写法。这样：
#      · 不误报 —— <xxx> 尖括号占位、'xxx' / "xxx" 引号占位、
#        $(openssl rand -hex 32) 命令替换、${VAR} 变量引用、
#        sed 表达式里的 `VAR=.*`（首字符是 . 且长度不足）
#      · 不漏报 —— 含 # ! / + = 等特殊字符的密钥同样命中
#        （旧版规则用白名单字符集，会漏掉含特殊字符的密钥）
#    README 的明文默认值以表格 + 反引号呈现（`GATEKEEPER_X` / `值` 分列），
#    不匹配本模式（要求 VAR=值 紧邻）；新增文档时请保持同样写法，否则本自检会开始报警。
git grep -nE "GATEKEEPER_(JWT_SECRET|AES_KEY|DB_PASSWORD|REDIS_PASSWORD)=[^[:space:]'\"<>\$][^[:space:]'\"<>\$]{15,}"

# ② 是否残留内网 IPv4 地址（192.168.1.x 属于文档举例网段，已排除）
git grep -nE "192\.168\.[0-9]+\.[0-9]+" | grep -vE "192\.168\.1\.[0-9]+"
```

> ②用于排查仓库内是否残留开发环境的内网主机地址。
> **本项目已完成脱敏**：`docs/` 下历史验证与设计文档中出现的真实内网 IP 已全部替换为
> `<MYSQL_HOST>` / `<REDIS_HOST>` 之类的占位符，本命令**预期无输出**。
>
> 两点如实说明：
> 1. 上述命令**只检查内网 IPv4 地址**，不覆盖端口号。`docs/` 中的历史记录可能仍保留当时的
>    非标准端口号——它不含主机信息，属于开发过程留痕，公开仓库时风险可接受；若你要求更严，
>    可在 fork 后一并脱敏。
> 2. 真实密钥所在的 `application.yml` 因被 `.gitignore` 忽略，`git grep` **搜索不到它**，
>    所以命令①②的"无输出"不代表磁盘上不存在密钥文件，只代表**它们不会进入版本库**。

- 暴露到公网前必须完成：修改 `admin` 默认密码、替换**全部四项密钥**（`GATEKEEPER_DB_PASSWORD` / `GATEKEEPER_REDIS_PASSWORD` / `GATEKEEPER_AES_KEY` / `GATEKEEPER_JWT_SECRET`）、`knife4j.enable: false`、按实际域名收敛 `gatekeeper.cors.allowed-origins`。

---

## 版本历史

| 版本 | 日期 | 主题 |
| --- | --- | --- |
| `v1.0.2` | 2026-09-29 | 按修复后的源码重建全部发布资产：镜像包内 `init.sql` 与源码对齐，部署链缺陷随镜像一并交付 |
| `v1.0.1` | 2026-09-29 | 补回数据大屏入口、空数据占位；版本号与镜像资产对齐；首次实机部署并修复 3 处部署链缺陷 |
| `v1.0.0` | 2026-09-28 | 首个正式版本：容器化、登录页去预填、默认密钥公开声明 |

### v1.0.2（2026-09-29）

**这是一次「发布物重建」版本，不含功能变更。** 目的是让 **GitHub Release 上的资产与修复后的源码完全对齐**——
`v1.0.1` 的镜像包是在部署链缺陷修复**之前**构建的，其内 `init.sql` 仍带缺陷 1（见下）。

**修复**

- **镜像 / jar 内嵌的 `init.sql` 已与源码逐字节一致**，部署链缺陷 1（第 1393 行多余逗号）不再存在于任何发布物中。
- 远端镜像包内的 `docker-compose.yml` 与 E2E 剧本同步为修复后版本（补挂 5 个结构脚本 + backend healthcheck）。

**工程**

- 版本号 `1.0.1` → `1.0.2`：`src/backend/pom.xml`、`src/frontend/package.json`（含 `package-lock.json`）、
  两个 `Dockerfile` 的 `ARG VERSION` 默认值。
- 后端 jar 仍按 §4.1 的闸门打包（模板顶替 + 立即还原），复检确认 jar 内配置仅含 `${...:localhost}` 占位。
- 前端产物与 `v1.0.1` **59/59 文件逐字节相同** —— 本版未改前端源码，仅版本号变化（`package.json.version` 不进 bundle）。
- 镜像 OCI 溯源标签 `org.opencontainers.image.revision` 指向本版 tag 的提交，`docker inspect` 可直接溯源。

**校验矩阵（本版）**

| 校验 | 结果 |
|---|---|
| jar 内 `application.yml` vs 模板 `application.example.yml` | **逐字节一致** |
| jar 内 `sql/init.sql` vs 源码 `init.sql` | **逐字节一致**（`7d14bb97…`） |
| 前端 `dist` vs `v1.0.1` 产物 | **59 / 59 文件逐字节一致** |
| Release 资产泄漏复检（内网主机 / 真实密钥） | `ALL_CLEAN = True` |
| 后端单测 | **780 tests / 0 failures** |

### v1.0.1（2026-09-29）

**修复**

- 补回「数据大屏」侧边栏入口（归入「监控与审计」组，免权限点），并在大屏页内新增「返回控制台」按钮 ——
  该页此前只能靠手输 `#/screen` 访问，侧边栏无任何入口。
- 大屏应用排行 / 接口热度两个 Top10 图表在接口返回空数组时不再留下整块无声空白，
  改为渲染「暂无数据」占位；同时对已初始化过的容器复用 ECharts 实例，避免重复 `init` 告警。

> 该现象是**数据缺口**而非功能缺陷：接口本身返回 200 且数据结构正确，只是库中暂无调用明细。
> 本轮只增加前端提示，未改动后端统计口径。

**工程**

- 版本号 `1.0.0` → `1.0.1`：`src/backend/pom.xml`、`src/frontend/package.json`（含 `package-lock.json`）、
  两个 `Dockerfile` 的 `ARG VERSION` 默认值。
- 重新构建并发布 `v1.0.1` 的完整资产（jar / 前端 zip / SQL zip / 两个镜像包 / Docker 构建包 / 两份校验和）。
- 修正 README「Docker 镜像」章节中的离线镜像资产文件名：实际为
  `gatekeeper-backend-image-<version>.tar` 与 `gatekeeper-frontend-image-<version>.tar`，
  此前误写为 `gatekeeper-backend-<version>.tar`。
- 新增 README §4.1「同一条闸门也适用于 Release 的 jar 资产」：明确发布用 jar 必须按
  Dockerfile 同口径打包（备份本地真实配置 → 模板顶替 → 打包 → 立即还原），
  并给出「jar 内不得出现内网库主机」的复检命令。
  **本版打包过程中正是这条复检拦下了一次真实泄漏**——直接用仓库工作区 `mvn package`
  得到的 jar，库地址指向内网实机、密钥为真实值，而镜像包因走 Dockerfile 反而是干净的，
  会出现「同一版本镜像干净、jar 泄漏」的不一致。

> **关于 v1.0.0 的溯源**：`v1.0.0` 的 tag 指向 `3ae1db9`，该提交不含 Dockerfile；
> 而 v1.0.0 的两个镜像包内 `org.opencontainers.image.revision` 为 `2e55dcc`（引入 Dockerfile 与密钥闸门的提交）。
> 自 `v1.0.1` 起，tag、源码提交与镜像 revision 三者已对齐。

**v1.0.1 补记 · 首次实机部署修复（2026-09-29）**

v1.0.1 发布后，在 CentOS 7.9 实机上按「一键部署」章节首次真机跑通 docker-compose 部署，
暴露出 3 个**只在真实 Docker 环境才会显现**的部署链缺陷，均已修复：

| # | 缺陷 | 影响 |
|---|---|---|
| 1 | `init.sql` 第 1393 行多一个逗号 | initdb 报 `ERROR 1064` 后中止 ⇒ 后续 10 个脚本全不执行，落库只剩 34 表 / 99 菜单 |
| 2 | `docker-compose.yml` 漏挂 5 个结构脚本 | 只有 36 张表（缺 6 张）⇒ 白名单 / 分组加解密 / 接口级加解密**抛 SQL 异常**；9 个权限点**连超管都拿不到** |
| 3 | 起栈后立即探活得到 502 | backend 就绪前有 8~15 秒窗口；已补 healthcheck + E2E 就绪等待 |

修复提交：`a5979e3`（缺陷 1）；缺陷 2、3 改动 `docker-compose.yml` 与 `docs/sql/t09-docker-e2e.sh`。
README「一键部署」章节据此重写（原为"未实测"的应然步骤），并新增 §5 常见问题与 §6 实测记录。
修复后 E2E **U0–U7 全绿**、部署态自检 **23/23**、表集合与开发库 **42/42 逐表一致**。

> ✅ **该遗留已在 `v1.0.2` 关闭**：v1.0.2 的镜像包内 `init.sql` 与源码**逐字节一致**，
> 上述「镜像包与源码不一致」的情况不再存在。
> 回顾 v1.0.1 的镜像包：其内 `init.sql` 带缺陷 1，但该差异**不影响 docker-compose 部署** ——
> compose 挂载的是宿主机的 `src/backend/src/main/resources/sql/init.sql`，镜像内那份不参与初始化。
> 只有「直接取镜像内 `init.sql` 手动导入」才会踩到缺陷 1；此类用法请改用 v1.0.2 及以后的镜像包。

### v1.0.0（2026-09-28）

首个正式版本。前后端容器化（多阶段构建、`.dockerignore` 密钥闸门、构建脚本前置校验），
登录页移除预填账号密码，Redis 增加可配置口令，并在 README 中明文公开全部默认账号与默认密钥。

---

## 许可证

本项目尚未添加 `LICENSE` 文件，**许可证待补充**。在补充许可证之前，默认保留所有权利（All rights reserved）。

## 致谢

感谢 Vue.js、Element UI、Spring Boot、MyBatis-Plus、Bouncy Castle、Hutool、ECharts 等优秀开源项目。
