# T09 · Docker E2E · 前置方案（Now 主线收口 / RICE 4.8）

> **定位**：Docker 一键起栈 + E2E 验收的**前置方案与阻塞清单**。本方案为 doc-only 产出，不碰 `src/**`、不动 git、不 commit。
> **⚠️ 头号事实（lead 07:35 实测，必须前置声明）**：**本机（Windows）无 docker 命令、无 Docker Desktop —— E2E 无法在本机执行。** 本方案不假装可验，所有「执行」步骤均为**待具备 Docker 环境后**的剧本（§5）；本轮可先行的是静态项（§6）。

---

## 1. 资产清单（三件已存在，本轮逐一实测）

Docker 化资产**不是从零开始**，以下三件已在仓库中（HEAD@7a2b6f5）：

### 1.1 `src/backend/Dockerfile`（多阶段，实测 22 行）
- 阶段1 `maven:3.8.8-eclipse-temurin-8`：先拷 pom.xml `dependency:go-offline`（层缓存）→ 拷 src → `mvn -B package -DskipTests`。
- 阶段2 `eclipse-temurin:8-jre`：仅拷 `target/gatekeeper.jar`；`ENTRYPOINT java -XX:MaxRAMPercentage=75.0 -jar app.jar`（容器内存感知，避免固定 Xmx 冲突）；`EXPOSE 8080`。
- ✅ 满足「Java 8 + maven build → JRE 运行」要求，无需改动。

### 1.2 `src/frontend/Dockerfile` + `nginx.conf`（实测）
- 阶段1 `node:16-alpine`：npmmirror 源 `npm install` → `npm run build`。
- 阶段2 `nginx:1.25-alpine`：托管 dist + `nginx.conf`（实测 37 行）：SPA `try_files` 回退 + **`/api/` 反代 `http://backend:8080`**（保留 /api 前缀，与 `vue.config.js:15-23` devServer proxy 行为一致）+ 静态缓存 + gzip。`EXPOSE 80`。
- ✅ 满足要求，无需改动。

### 1.3 `docker-compose.yml`（根目录，实测 87 行）
- 4 服务：`mysql:8.0`（utf8mb4）+ `redis:7-alpine` + `backend`（build ./src/backend）+ `frontend`（build ./src/frontend，端口 `8081:80`）。
- 健康检查：mysql `mysqladmin ping`（`$$` 转义防 `docker inspect` 泄口令）、redis `redis-cli ping`；`depends_on: condition: service_healthy`（backend 等 mysql+redis 健康）。
- 卷：`mysql-data` / `redis-data` / `export-data`（挂 `/app/data/exports` 对应 `GATEKEEPER_EXPORT_DIR`，`application.yml:103`）。
- ⚠️ **既有缺陷（P0，见 §3.2）**：mysql 初始化只挂载 `./src/backend/src/main/resources/sql/init.sql`（`:22`）——该文件是 **T01 时代旧 schema**。

### 1.4 配套文件（实测均存在）
- `.env.example`（根目录，23 行）：3 必填密钥 + 选填（DB_NAME/DB_USERNAME/CORS_ORIGINS）；`.gitignore:34-36` 忽略 `.env*`（保留 example）。
- `src/backend/src/main/resources/sql/init.sql`：存在，但内容过时（§3.2）。

---

## 2. 密钥处理（对照 pre-commit 盯防口径）

### 2.1 现状达标项（实测）

| 密钥 | 配置键（application.yml） | 环境变量 | compose 注入 | 强制度 |
|---|---|---|---|---|
| JWT | `gatekeeper.jwt.secret`（:75） | `GATEKEEPER_JWT_SECRET` | `:59` | `:?` 缺失即拒起 + 启动自检 ≥32 位 |
| AES | `gatekeeper.crypto.aes-key`（:72） | `GATEKEEPER_AES_KEY` | `:60` | 同上 |
| DB 口令 | `spring.datasource.password`（:17） | `GATEKEEPER_DB_PASSWORD` | mysql `:16` + backend `:55` | 同上（mysql root 初始化同源） |

- **fail-fast 启动自检**（`SecurityStartupCheck.java`）：三密钥缺失/过短/命中历史默认值黑名单 → 拒绝启动，且**先于 Web 端口开启**。
- **Dockerfile 与 compose 内零明文密钥**（逐行实测确认）：compose 用 `${VAR:?}` 强制从 `.env` 读取；mysql healthcheck 用容器内运行时变量 `$$MYSQL_ROOT_PASSWORD`，不内联。
- DB/AES/JWT 的 host、port、name、username 等非密钥项也全走环境变量占位（`application.yml:14-16`）。

### 2.2 ⚠️ 与 lead 口径的差异：第 4 处「Redis 口令」现状不存在（✅ 已裁定：不做）

lead 派工口径「pre-commit 盯的 4 处：DB 口令/**Redis 口令**/AES/JWT」。**实测现状只有 3 处**：
- `application.yml:26-30`：`spring.redis` 仅 host/port/database/timeout，**无 `password` 键**（注释原文「当前 Redis 未启用密码，如需可自行补充」）；
- 后端全代码 `grep redis.*password` **零引用**；
- compose redis 服务无 `--requirepass`；`.env.example` 无 `GATEKEEPER_REDIS_PASSWORD`。

**✅ 裁定：不做对齐，保持现状 3 密钥（lead，2026-09-12）**。理由：`spring.redis` 在 application.yml 里根本没有 password 配置行，compose 内 Redis 若加 `requirepass` 反而**直接断连**；本地栈内网隔离，风险不成立。（原「对齐方案」存档：`spring.redis.password: "${GATEKEEPER_REDIS_PASSWORD:}"` + compose `--requirepass` + backend env 传递 + `.env.example` 补行，演进式 4 行——将来若出内网部署再启用。）

### 2.3 已知债（P2，留档不阻塞）：application.yml 占位默认值即真实密钥

实测 `application.yml:17/72/75` 三个占位符**默认值是真实可用密钥**（DB 口令与远程库实连一致；AES/JWT 为 32+ 位真实值）。该文件按 lead 口径未被 git 跟踪，且全仓 docs/test grep 该三串**零命中**（密钥唯一载体就是此本地文件）。风险与缓解：
- **容器路径已安全**：compose 三变量均 `:?` 强制，容器内不会回退到默认值；
- **裸 `java -jar` 路径依赖默认值**（已知债，缓解已落地）：`.gitignore` **已补 `src/backend/src/main/resources/application.yml` 条目**（lead 提交于 `b3a0759`，含注释），误 `git add` 风险已闭环。备选方案「仓库内 application.yml 全空默认值 + 本地真值走 application-local.yml」改动面大，仅留档不实施。

---

## 3. 🔴 两大执行阻塞（本方案核心产出）

### 3.1 阻塞 A：本机无 Docker（lead 07:35 实测）

**事实**：本机（Windows）无 `docker` 命令、无 Docker Desktop。E2E 的起栈/冒烟/停栈全部**不可在本机执行**。

**执行环境前提（三选一，待用户/lead 决策）**：
1. 本机安装 Docker Desktop（需 WSL2 后端）；
2. 远程 Docker 主机：`export DOCKER_HOST=ssh://user@host` 后照常执行（compose 无需改动）；
3. CI 环境（若引入）。

**不得**为「验证」目的在本机伪造 docker 命令或跳过起栈只验编译——那属于 T07/T08 已定性的「绿灯漏检」。

### 3.2 阻塞 B（新发现，P0）：init.sql 是 T01 旧 schema，E2E 栈起不来业务

**实测**（`grep CREATE TABLE|INSERT INTO` 于 `src/backend/src/main/resources/sql/init.sql`）：
- 仅建 **15 张 T01 表**（app / app_ip_whitelist / app_rate_limit / api_group / api_interface / api_encryption_config / app_encryption_config / app_api_permission / api_call_log / ip_ban / security_event / security_rule / sys_user / sys_role / sys_user_role / sys_operation_log / alert / export_task）+ 少量种子（admin 用户 bcrypt、1 角色、1 安全规则）。
- **缺全部 v2 演进表**：`notify_channel`、`alarm_rule`、`sys_menu`、`sys_role_menu`、`sys_dict`、`sys_dict_item`、`sys_config`、`block_rule`、授权域表、数据权限表等（18 表 vs 15 表差额）。
- **缺全部权限种子**（sys_menu type=3 的 80 码 + sys_role_menu 授权）。

**后果链**：compose 起栈 → mysql 只建 T01 表 → backend 起来、登录可用（`POST /api/auth/login` admin/admin123 200，init.sql:297-298 bcrypt 种子）→ **但**：①`PermissionInterceptor` 精确集合匹配、无超管通配 ⇒ perm 缓存空 ⇒ **所有挂注解端点 403**（应用 CRUD、接口管理全灭）；②告警/通知/授权/字典/配置域**表不存在 ⇒ SQL 报错 500**。**E2E 冒烟清单（§4）按现状一项都过不了。**

**修复方案（幂等、不动源文件名）**：compose mysql 的 initdb 挂载由单文件改为**有序多文件**（`docker-entrypoint-initdb.d` 按文件名字典序执行，用挂载 target 名排序，`docs/sql/` 源文件名不动）：

```yaml
    volumes:
      - mysql-data:/var/lib/mysql
      # v2 初始化链（按执行顺序重命名挂载；源文件名不变，避免破坏历史文档引用）
      - ./src/backend/src/main/resources/sql/init.sql:/docker-entrypoint-initdb.d/00-t01-base.sql:ro
      - ./docs/sql/schema-v2.sql:/docker-entrypoint-initdb.d/01-schema-v2.sql:ro
      - ./docs/sql/migrate-v2.sql:/docker-entrypoint-initdb.d/02-migrate-v2.sql:ro
      - ./docs/sql/t02-seed-admin-role.sql:/docker-entrypoint-initdb.d/03-t02-seed.sql:ro
      - ./docs/sql/t03a-seed-permissions.sql:/docker-entrypoint-initdb.d/04-t03a-seed.sql:ro
      - ./docs/sql/t03b-seed-permissions.sql:/docker-entrypoint-initdb.d/05-t03b-seed.sql:ro
      - ./docs/sql/seed-perm-alignment.sql:/docker-entrypoint-initdb.d/06-t05-seed.sql:ro
      - ./docs/sql/seed-datascope-perm.sql:/docker-entrypoint-initdb.d/07-t05-datascope.sql:ro
      - ./docs/sql/t07a-seed-rules.sql:/docker-entrypoint-initdb.d/08-t07a-seed.sql:ro
      - ./docs/sql/t08-seed-perms.sql:/docker-entrypoint-initdb.d/09-t08-seed.sql:ro
      - ./docs/sql/t09-hygiene.sql:/docker-entrypoint-initdb.d/10-t09-hygiene.sql:ro
```

依据与注意：
- 顺序链基于「schema-v2.sql 在 T01 表基础上 CREATE 8 新表（IF NOT EXISTS）+ ALTER 10 表」（`schema-v2.sql:710-713` 注释实测）⇒ **00 基础表必须先行**；
- 种子按批次 T02→T03a→T03b→T05→T07a→T08→T09 排序（各文件内部已幂等：`INSERT IGNORE` + uk 约束，T05/T08 契约已定）；
- initdb **仅在 mysql 数据卷为空时执行** ⇒ E2E 必须 `docker compose down -v` 全清后再起（§4 U0/U7 强制）；
- 该 compose 改动本身**可在无 Docker 环境下静态 review**（YAML 语法 + 挂载路径存在性），执行验证待阻塞 A 解除。

**📋 方案 v1.1 勘误 + T10-D 施工核对（本节由 architect-2 于 T10-D 后认领）**

> **存档口径（性质区分，勿混）**：本节记录的是**本方案自身的实质遗漏**（v1.0 写错），**不是**施工方偏离原文——两类问题归档时必须分开。

**勘误项 E1：§3.2 对 `t09-hygiene.sql` 的必需性误标（方案 v1.0 自身错误）**
- **原文缺陷**：v1.0 的挂载 YAML **已列入** `10-t09-hygiene.sql`，但「依据与注意」把它标注为「lead 数据卫生产出、**挂载前需 lead 确认终稿**」——**把 fresh bootstrap 必需件误标为可选项**。该标注会向读者暗示「可不挂」；一旦读者据此省略，空卷首跑终态 `sys_menu` = **113 行 ≠ dev 库现状 112 行**，T09-B 修掉的「授权树双业务线管理」缺陷会在新环境**种回去**。其后果与「清单漏列该文件」等同 ⇒ 定性为**方案的实质遗漏**。
- **成因（T10-D 实证）**：`t03a-seed-permissions.sql:33` **跨脚本重复播种** `sys_menu.id=221`（`(221, 5, '业务线管理', 2, NULL, '/sys/bizline', 0, 3, 1)`，与 `migrate-v2.sql:136` 播的 id=212 同值）⇒ `t09-hygiene.sql` 语句 1（七重守卫 DELETE）**空卷首跑实删 1 行、非 no-op**，是「先播坏行、链尾删掉」的**必需闭环**。
- **v1.1 修正**：删除原「待确认终稿」注意项；明确 `10-t09-hygiene.sql` 为 **fresh bootstrap 必需件（非可选）**，其终稿已确认并提交（`7349f7f`）。

**T10-D 施工核对结论（eng-shape，交叉印证）**
- **实挂链 = 11 文件（00–10，含 hygiene）**，与 v1.1 清单一致。
- **裁定**：保留 hygiene 挂载（理由 = 「初始化链 ＝ 历史时刻完整快照，可回放、可审计」，replay-then-govern）。
- **T11 候选**：删除源 `t03a-seed-permissions.sql:33` 那行（本轮**不做**——历史冻结种子，改内容破坏契约与 git blame；⚠️ **禁止**下一轮以「清理」为由顺手删源）。详见 `docs/T10-D-初始化链幂等性核对与T11候选.md`（另含 U2b 断言：`sys_menu` 总数=112、`id=221`=0）。
- **归因订正（lead 共同纠偏）**：野行 221 **非**「种子外野行」，实为 **t03a 跨脚本重复播种**；hygiene 脚本注释已订正（`7349f7f`）。

### 3.3 E2E 冒烟对「上游 mock」的依赖（设计项）

「网关转发」用例需要接口环境配置指向一个真实上游。**无外联环境**拉不到 httpbin 等新镜像 ⇒ 在 compose 追加可选服务 `mock-upstream`（复用 frontend 已用的 `nginx:1.25-alpine` 镜像，**零新增镜像下载**）：

```yaml
  mock-upstream:
    image: nginx:1.25-alpine
    container_name: gatekeeper-mock-upstream
    restart: unless-stopped
    configs: [mock-upstream-conf]          # 或挂载只读 conf：location / 返回 200 JSON
```

（具体 conf 以「`location / { return 200 '{"mock":"true"}'; }`」为最小实现；E2E 时接口环境 `upstreamUrl` 填 `http://mock-upstream:80`。）

---

## 4. E2E 验收用例（待 Docker 环境后执行的剧本）

统一前置：`cp .env.example .env` 并填三密钥（`openssl rand -base64 32` 生成）；admin 口令 `admin/admin123`（docs/sql/t03b-e2e.sh:16 既有范式；`init.sql:297-298` bcrypt 种子实测）。

| # | 步骤 | 命令（要点） | 期望 |
|---|---|---|---|
| U0 | 全清起栈 | `docker compose down -v 2>/dev/null; docker compose up -d --build` | 4（+可选 mock）容器 Up；**首次必须 down -v 保证 initdb 执行** |
| U1 | 基础设施健康 | `docker compose ps` | mysql/redis `healthy`；backend/frontend Up |
| U2 | 后端探活 | `curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/doc.html`（knife4j enable=true，application.yml:63-66） | **200**（探活不依赖业务表） |
| U3 | 登录 | `curl -X POST http://localhost:8081/api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'` | code=0 + token；**若 403/500 ⇒ §3.2 初始化链未生效，先修阻塞 B** |
| U4 | 应用 CRUD 冒烟 | 带 token：`POST /api/app`（app:create，admin=ADMIN 角色全量种子）→ `GET /api/app/list` → `PUT` → `DELETE` | 全 200/204，list 可见新建（验证权限种子 + 表结构双链路） |
| U5 | 网关转发 + 日志落库 | 配接口/版本/环境（upstreamUrl=`http://mock-upstream:80`，走 set-current 切生产）→ 用应用凭证调网关转发端点 → `GET /api/log/list?appId=N` | 转发返回 mock JSON；`total>=1`（**带数据的正面断言**，承 Class G 铁律：不验「不报错」） |
| U6 | 异步导出 | `POST /api/log/export` → taskId → 轮询 `GET /api/log/export/tasks` 至 SUCCESS → `GET /api/log/export/{taskId}/download` | 200 + CSV 文件（Content-Disposition） |
| U7 | 停栈清理 | `docker compose down -v` | 容器/卷全清（防脏数据影响下次 initdb） |

> **验收纪律（承 T07/T08）**：U5 的 `total>=1`、U4 的「list 可见新建」是**能失败的用例**；只验「容器 Up / 接口不 500」属绿灯漏检。E2E 脚本建议落 `docs/sql/t09-docker-e2e.sh`（对齐 `docs/sql/t03b-e2e.sh` 惯例，`.gitignore` 不误伤），**本轮 doc-only 不落脚本**，剧本以本表为准。

---

## 5. 明确「本机无 Docker」阻塞段（承 §3.1）

- **不可执行**：U0–U7 全部、镜像构建、compose config 之外的任何 docker 命令。
- **可先行（静态）**：见 §6。
- **解除条件**：三选一（装 Docker Desktop / 远程 DOCKER_HOST / CI），由用户/lead 决策；解除后按 §4 剧本一次跑通。

---

## 6. 与现有启动方式的关系（演进式承诺）

- 本地开发流（`mvn spring-boot:run` + `npm run serve`，devServer proxy → localhost:8080）**完全不受影响**：Docker 资产是旁路新增，不改 application.yml 的占位符默认值语义（本地不设环境变量即用默认值，容器内被 compose `:?` 强制覆盖）。
- compose 与本地共用同一 `application.yml` 的环境变量占位机制（DB host/port/name/user/pwd、Redis host、JWT/AES/CORS、EXPORT_DIR 全占位，application.yml:14-17/28/72-78/103 实测）——**单一配置源、双运行形态**。
- 生产建议（留档）：`GATEKEEPER_CORS_ORIGINS` 改实际域名；mysql 卷做备份策略；`restart: unless-stopped` 已就位。

---

## 7. 静态先行清单（本轮无 Docker 也可推进）

| 项 | 状态 | 说明 |
|---|---|---|
| S1 Dockerfile ×2 review | ✅ 本轮已完成 | §1.1/§1.2，结论：无需改动 |
| S2 compose 密钥面 review | ✅ 本轮已完成 | §2.1，零明文、`:?` 强制、healthcheck 防泄露 |
| S3 初始化 SQL 链组装 | 📝 方案已给（§3.2），**待施工** | 改 compose volumes（10 行挂载）+ 静态核对每个文件存在性与幂等性；可在无 Docker 环境 review YAML |
| S4 Redis 口令对齐（第 4 密钥） | 🟢 **已裁定：不做**（lead，2026-09-12） | §2.2：现状 3 密钥保持；compose 加 requirepass 反而断连，内网隔离风险不成立 |
| S5 mock-upstream 服务 | 📝 方案已给（§3.3），待施工 | 复用 nginx 镜像，零新增下载 |
| S6 E2E 脚本 | 📝 剧本已给（§4），待环境 | 解除阻塞 A 后落地执行 |
| S7 远程库快赢 2 核对 | ✅ 已满足（lead 实测） | `api_call_log` 10 个组合索引已存在、表 0 行——E2E 落库即有索引可用，无需补 DDL |

---

## 8. 风险表

| # | 项 | 级别 | 处置 |
|---|---|---|---|
| R1 | 本机无 Docker ⇒ E2E 无法执行 | 🔴 P0 | §3.1/§5，三选一解除；不得伪造验证 |
| R2 | init.sql 旧 schema ⇒ 栈起不来业务 | 🔴 P0 | §3.2 初始化链（本方案核心增量） |
| R3 | initdb 仅空卷执行 ⇒ 脏卷导致「改了没生效」 | 🟠 P1 | U0/U7 强制 `down -v`；剧本写死 |
| R4 | 种子文件间顺序/幂等缺陷（如 t09-hygiene 未终稿） | 🟠 P1 | S3 静态逐一核对；hygiene 挂载前 lead 确认 |
| R5 | Redis 无口令与 lead 口径不一致 | 🟢 **已裁定闭环** | §2.2：保持现状 3 密钥（lead，2026-09-12）；加 requirepass 会断连，内网隔离风险不成立 |
| R6 | application.yml 默认值含真钥（裸 jar 路径） | 🟡 P2 | §2.3 留档；容器路径已有双保险 |
| R7 | 无外联环境拉不到新镜像 | 🟢 已规避 | mock-upstream 复用既有 nginx 镜像（§3.3） |
| R8 | E2E 用例沦为「绿灯漏检」 | 🟠 P1 | §4 验收纪律：带数据正面断言 |

---

## 9. 关联文档

- `docs/sql/t03b-e2e.sh`（11 步 curl E2E 既有范式、admin/admin123 登录样板）
- `docs/T08-权限执行缺口-契约记录.md`（无超管通配、未播种=全员 403 —— §3.2 后果链依据）
- `docs/sql/schema-v2.sql` / `migrate-v2.sql`（18 表 + ALTER 10 表，初始化链 01/02 号位）
- `docs/路线图-RICE优先级评分.md`（Docker E2E=Now 主线 4.8；告警渠道 3.6 / ESLint 3.0 / 监控指标 2.8 为 Next）
