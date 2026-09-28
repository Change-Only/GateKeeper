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
- ✅ **已根治（原 P0，见 §3.2）**：mysql 初始化挂载 `./src/backend/src/main/resources/sql/init.sql`（`:22`）——该文件已由 eng-db-init **重建为线上库忠实基线**（1513 行 / **36 张表** / 自带 9 张基线种子表），不再是 T01 旧 schema。

### 1.4 配套文件（实测均存在）
- `.env.example`（根目录，23 行）：3 必填密钥 + 选填（DB_NAME/DB_USERNAME/CORS_ORIGINS）；`.gitignore:34-36` 忽略 `.env*`（保留 example）。
- `src/backend/src/main/resources/sql/init.sql`：**已重建为线上库忠实基线**（1513 行 / 36 表 / 9 基线种子表）—— 原「内容过时」描述作废，见 §3.2。

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

> **📌 后续更新（2026-09-28）：本条裁定已被推翻，上述「存档方案」已实际落地。**
> 按用户要求新增 Redis 访问密码，改动与上面存档的 4 点一致，并额外提供**强随机默认值**：
> - `spring.redis.password: "${GATEKEEPER_REDIS_PASSWORD:GkRedis#9fQ2mL7pX!4sT8nB}"`（`application.yml` 与 `application.example.yml` 同步）；
> - `docker-compose.yml` 的 redis 服务加 `--requirepass`（口令经**运行时环境变量** `$$GATEKEEPER_REDIS_PASSWORD` 传入，不内联进 command；
>   healthcheck 改用 `REDISCLI_AUTH=$$GATEKEEPER_REDIS_PASSWORD redis-cli ping`，避免无鉴权探活恒失败）；
> - backend 服务与 `.env.example` 补 `GATEKEEPER_REDIS_PASSWORD`；
> - README 新增「默认账号与密钥」章节公开默认口令，FAQ Q5 补充「口令不一致表现与 Redis 未启动相同」。
>
> 已实测验证：无口令 `NOAUTH`、正确口令 `PONG`、错误口令被拒、后端登录与 `gk:perm:*` 缓存写入正常（11/11 断言通过）。
> 上面 2026-09-12 的「实测现状」与"裁定"**保留为历史记录，不再代表当前实现**。

### 2.3 已知债（P2，留档不阻塞）：application.yml 占位默认值即真实密钥

实测 `application.yml:17/72/75` 三个占位符**默认值是真实可用密钥**（DB 口令与远程库实连一致；AES/JWT 为 32+ 位真实值）。该文件按 lead 口径未被 git 跟踪，且全仓 docs/test grep 该三串**零命中**（密钥唯一载体就是此本地文件）。风险与缓解：
- **容器路径已安全**：compose 三变量均 `:?` 强制，容器内不会回退到默认值；
- **裸 `java -jar` 路径依赖默认值**（已知债，缓解已落地）：`.gitignore` **已补 `src/backend/src/main/resources/application.yml` 条目**（lead 提交于 `b3a0759`，含注释），误 `git add` 风险已闭环。备选方案「仓库内 application.yml 全空默认值 + 本地真值走 application-local.yml」改动面大，仅留档不实施。

> **📌 后续更新（2026-09-28）：本节的「零命中」前提已不再成立。**
> 按用户明确要求（仓库为 public），README 新增[「默认账号与密钥」](../../README.md#默认账号与密钥)章节，
> **明文公开**了本地默认的 **DB 口令**与 **JWT 密钥**（AES 密钥此前已存在于公开提交历史中），
> 并新增 Redis 默认口令。因此：
> - 上面「全仓 grep 该三串零命中 / 密钥唯一载体就是此本地文件」**自 2026-09-28 起为假**；
> - `.gitignore` 对 `application.yml` 的忽略**仍然有效**（该文件依旧不入库），但**保密性已丧失**——
>   这几串值此后应一律按「已泄露」对待；
> - 本笔属**知情取舍**（用户已确认），代价是「裸 `java -jar` 路径依赖默认值」这条已知债
>   从"仅本地"升级为"公网可见"，缓解手段只剩**部署前强制替换**（`SecurityStartupCheck` 对
>   "仍等于 yml 内置默认值"在生产环境已 fail-fast，是当前唯一自动化防线）。

---

## 3. 执行阻塞与架构结论变更（本方案核心产出）

### 3.1 阻塞 A：本机无 Docker（lead 07:35 实测）

**事实**：本机（Windows）无 `docker` 命令、无 Docker Desktop。E2E 的起栈/冒烟/停栈全部**不可在本机执行**。

**执行环境前提（三选一，待用户/lead 决策）**：
1. 本机安装 Docker Desktop（需 WSL2 后端）；
2. 远程 Docker 主机：`export DOCKER_HOST=ssh://user@host` 后照常执行（compose 无需改动）；
3. CI 环境（若引入）。

**不得**为「验证」目的在本机伪造 docker 命令或跳过起栈只验编译——那属于 T07/T08 已定性的「绿灯漏检」。

### 3.2 阻塞 B（原 P0，**已根治**）：init.sql 基线重建 + 挂载链退化为「历史回放」

> **存档口径（务必区分，勿混）**：阻塞 B 的处置经**两个性质不同**的阶段 —— **v1.0** 用「11 文件挂载链」**绕过**旧 init.sql（**workaround**）；**v1.2** 由 eng-db-init **重建 init.sql 本身**（**根治**）。归档时必须分开，否则后人会误以为 init.sql 仍是坏的。

#### 3.2.1 根治结果（eng-db-init，lead 实测 + 本文档复核）

- `init.sql`：416 行 → **1513 行 / 36 张表**（本文档复核：行首 `CREATE TABLE` 计数 = **36**）；按 FK 拓扑排序、`ON DUPLICATE KEY UPDATE` 幂等。
- **自带 9 张基线种子表**（本文档复核 `INSERT INTO` 目标表 = 9：`sys_user` / `sys_role` / `sys_menu` / `sys_user_role` / `sys_role_menu` / `sys_role_datascope` / `sys_dict` / `sys_dict_item` / `sys_config`），逐项与线上库吻合（lead 实测：`sys_menu`=112 / `sys_role_menu`=409 / `sys_role`=9 / `sys_user`=1 / `sys_dict`=6 / `sys_dict_item`=22 / `sys_config`=19 / `sys_role_datascope`=2 / `sys_user_role`=2）。
- `AUTO_INCREMENT=` 残留 **0** 处、`CREATE PROCEDURE` **0**（本文档复核）。已用 `zzck_` 前缀临时表在活库做等价导入校验，**结构 diff 为空**。
- ⚠️ **`security_rule` 无 INSERT**（本文档复核 `INSERT INTO` 列表不含该表）—— 与线上一致，见 §3.2.3 裁定 **A2**。

**原 v1.0 描述（已不成立，作废留档）**：「init.sql 仅建 15 张 T01 表、缺 18 张 v2 演进表与全部权限种子 ⇒ 刷栈后挂注解端点全 403、告警/授权/字典域 500」——该结论**对 T01 旧版成立、对重建版不成立**，**勿再引用**。原 v1.0 曾据此给的「后果链」（登录可用但权限空 ⇒ 403/500）亦随之消解。

#### 3.2.2 挂载链现状：`01–10` 退化为「历史回放」（裁定：本轮不动）

**11 文件挂载链仍保留**（T10-D 已落地，勘误见 §3.2.4），但 **init.sql 重建后，它由「T01 基础表」升格为「唯一权威 bootstrap」**，其下 `01–10` 的语义随之由「补缺」变为**冗余回放**。lead 核定其与 init.sql 的相互作用**无破坏**，论证：

- **过程定义自洽**：init.sql **0 个 `CREATE PROCEDURE`**（本文档复核）⇒ `schema-v2.sql:29/45` 定义的 `gk_add_column`/`gk_add_index`（**不带 IF NOT EXISTS**）在 `01` 空卷首跑时**不报错**（因过程尚不存在），到 `02` 才创建并复用 —— 顺序恰好自洽。
- **冗余种子全 no-op、不覆盖权威值**：`03–09` 的基线数据 init.sql 已全含，且 7 个 seed **全为 `INSERT IGNORE`（本文档实测 **48** 处；`ON DUPLICATE KEY UPDATE` **0** 处）** ⇒ 全 **no-op**，**不会覆盖** init.sql 的权威值（这是链条「无害」的**安全性论据**）。
- **终值一致**：`t03a-seed-permissions.sql:33` 仍插 `sys_menu.id=221`、`t09-hygiene.sql` 仍删它 ⇒ `sys_menu` 终值 **112**，U2b 断言（§3.2.4）仍成立。

**▶ T11 候选（记档）**：init.sql 现为**唯一权威 bootstrap**、`01–10` 为**冗余回放** ⇒ **链条简化**（去冗余 seed 挂载）应作为 **T11** 议题。**理由**：冗余种子携带的历史数据长期有与 init.sql **漂移**的风险 —— 现靠 `INSERT IGNORE` 兜住，**一旦有人把 seed 改成 `ON DUPLICATE KEY UPDATE` 就会静默覆盖** init.sql 的权威值。

**挂载链（compose mysql initdb；`docker-entrypoint-initdb.d` 按 target 名字典序执行，源文件名不动）**：

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
- 顺序链基于「`schema-v2.sql` 新建 **18** 表（`CREATE TABLE IF NOT EXISTS`）+ ALTER **10** 存量表」（`schema-v2.sql:61 / :447` 实测）⇒ **00 基础表必须先行**。⚠️ 注：init.sql 重建后**已含全部 18 表** ⇒ `01` 的**新建部分亦为 no-op**（IF NOT EXISTS 跳过），仅 ALTER 部分经 `gk_add_column` 过程幂等生效 —— 同属「历史回放」（呼应 §3.2.2）；
- 种子按批次 T02→T03a→T03b→T05→T07a→T08→T09 排序（各文件内部已幂等：`INSERT IGNORE` + uk 约束，T05/T08 契约已定）；
- initdb **仅在 mysql 数据卷为空时执行** ⇒ E2E 必须 `docker compose down -v` 全清后再起（§4 U0/U7 强制）；
- 该 compose 改动本身**可在无 Docker 环境下静态 review**（YAML 语法 + 挂载路径存在性），执行验证待阻塞 A 解除。

#### 3.2.3 裁定归档（本轮 A1/A2，显式留档防下一轮重复）

- **A1 · `api_call_log` 索引**：**取线上 9 个二级索引，不塞原先想要的 5 个组合索引**。**有意偏离 + 丢失理由**：硬约束是「线上库 = 唯一事实来源 + diff 必须为空」，且那 5 个组合索引**无任何真实查询模式依据**（在 25 列日志表上凭猜加索引 = 纯写入放大）；真需要时凭 `EXPLAIN` 实测再补。⚠️ **下一轮勿当「漏了 5 个」再补一遍**。
- **A2 · `security_rule` 默认规则**：**不加回 init.sql**（本文档复核 `INSERT INTO security_rule` = 0，与线上一致）。开源版若想要默认安全规则，属**发布包装决策** —— 另开可选种子文件走产品评审，**不夹带进基线**。

#### 3.2.4 方案 v1.1 勘误 + T10-D 施工核对（本节由 architect-2 于 T10-D 后认领）

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
| U3 | 登录 | `curl -X POST http://localhost:8081/api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'` | **code=200**（`Result.success()` 置 200，非 0；承 T10-E C1）+ `data.token`；**若 403/500 ⇒ 初始化链异常（init.sql 基线或 01–10 回放，见 §3.2）** |
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
| S3 初始化 SQL 链组装 | ✅ 已施工（T10-D，静态验证） | compose volumes 11 文件挂载已落地（T10-D `7349f7f`）；**init.sql 另已根治**（v1.2）⇒ 链退化为历史回放，见 §3.2 |
| S4 Redis 口令对齐（第 4 密钥） | 🟢 **已裁定：不做**（lead，2026-09-12） | §2.2：现状 3 密钥保持；compose 加 requirepass 反而断连，内网隔离风险不成立 |
| S5 mock-upstream 服务 | 📝 方案已给（§3.3），待施工 | 复用 nginx 镜像，零新增下载 |
| S6 E2E 脚本 | 📝 剧本已给（§4），待环境 | 解除阻塞 A 后落地执行 |
| S7 `api_call_log` 索引口径 | 🟢 已裁定 | **取线上 9 个二级索引**，**有意不塞** 5 个组合索引（无查询模式依据，凭猜加索引=写入放大）+ 丢失理由见 §3.2.3 A1 |

---

## 8. 风险表

| # | 项 | 级别 | 处置 |
|---|---|---|---|
| R1 | 本机无 Docker ⇒ E2E 无法执行 | 🔴 P0 | §3.1/§5，三选一解除；不得伪造验证 |
| R2 | init.sql 旧 schema ⇒ 栈起不来业务 | 🟢 **已关闭（根治）** | §3.2：init.sql 已重建为 **36 表线上基线**（**根治**，非 workaround）；风险已消解 |
| R3 | initdb 仅空卷执行 ⇒ 脏卷导致「改了没生效」 | 🟠 P1 | U0/U7 强制 `down -v`；剧本写死 |
| R4 | 种子文件间顺序/幂等缺陷 | 🟢 已核对（T10-D） | hygiene 终稿 `7349f7f`；01–10 全 `INSERT IGNORE`（48 处，ON DUP 0）⇒ 不覆盖 init.sql 权威值，链条无害 |
| R5 | Redis 无口令与 lead 口径不一致 | 🟢 **已裁定闭环** | §2.2：保持现状 3 密钥（lead，2026-09-12）；加 requirepass 会断连，内网隔离风险不成立 |
| R6 | application.yml 默认值含真钥（裸 jar 路径） | 🟡 P2 | §2.3 留档；容器路径已有双保险 |
| R7 | 无外联环境拉不到新镜像 | 🟢 已规避 | mock-upstream 复用既有 nginx 镜像（§3.3） |
| R8 | E2E 用例沦为「绿灯漏检」 | 🟠 P1 | §4 验收纪律：带数据正面断言 |

---

## 9. 关联文档

- `docs/sql/t03b-e2e.sh`（11 步 curl E2E 既有范式、admin/admin123 登录样板）
- `docs/T08-权限执行缺口-契约记录.md`（无超管通配、未播种=全员 403 —— 权限执行缺口的背景依据，非本链结论）
- `docs/sql/schema-v2.sql` / `migrate-v2.sql`（schema-v2 新建 18 表 + ALTER 10 表，初始化链 01/02 号位）
  - ⚠️ **勘误**：`schema-v2.sql:4` 头部仍写「执行前提：已执行 init.sql（**18 张存量表**）」——该描述对应 **T01 时点**状态、**已过期**（init.sql 现为 **36 表**）。按项目惯例**不追改历史迁移脚本**，仅此说明；引用时勿以其为当前基线。
- `docs/T10-D-初始化链幂等性核对与T11候选.md`（幂等性两维核对、C1–C8 修正、U2b 断言、T11 候选）
- `docs/路线图-RICE优先级评分.md`（Docker E2E=Now 主线 4.8；告警渠道 3.6 / ESLint 3.0 / 监控指标 2.8 为 Next）
