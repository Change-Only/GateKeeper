# GateKeeper → APIM 演进式重构 · 总体设计总纲

> 本文档是**一页纸执行总纲**，定位、范围、原则、关键决策、分期与验收、待决项拍板、起手动作都在这里。
> 详细 PRD 见 `docs/PRD-APIM重新设计.md`（1020 行），架构见 `docs/架构设计-APIM重新设计.md`，枚举字典见 `docs/原型枚举字典.md`。

---

## 一、产品定位（一句话）

**面向企业的 API 全生命周期管理平台（APIM）**：以「**接口资产 + 应用身份 + 授权关系**」三张主数据为核心，把接口从「**登记、版本演进、发布、授权、联调**」到「**运行观测、异常告警、封禁止损、合规审计**」完整收口；网关只是它的**执行面**。

## 二、演进本质

| 维度 | GateKeeper 现状 | APIM 目标 |
|---|---|---|
| 关键词 | 管控（谁能调、能不能拦住） | 资产 + 治理（有哪些接口、谁在调、调得怎样、变更过什么、出事怎么止损） |
| 接口视角 | 转发的路径 | 资产（有参数/错误码/版本/SLA/变更历史） |
| 应用视角 | 一套密钥 | 多套凭证（prod/test/pre 分开 + 轮换） |
| 授权视角 | 是/否授权 | **环境 × 配额 × 有效期 × 理由 × 审批** |
| 安全能力 | 主角 | 保障面（不退场） |
| 运维视角 | 改 yml 重启 | 字典/配置/告警/通知渠道全可运营 |

## 三、铁律（不允许违反）

1. **演进式重构，不推翻重写**。现有 91 个单测、网关责任链、5 类安全检测、Redis fail-open 降级、百万级异步导出 —— 全部**只补齐不重做**。
2. **现有 12 个 Controller 一个都不废弃**，全部保留并扩展；新增 10 个域 Controller。
3. **现有 18 张表全部保留**，加解密/安全/导出/用户等不变；只加列、加索引、加新表。
4. **存量数据零迁移失败**：所有迁移脚本幂等可重复执行；status 枚举冲突采用"加新列承载原型语义 + 存量列不动"。
5. **双套枚举**：`alert.status` 是告警处理态（0未读/1已读/2已处理/3已忽略），与 `apps.status` 启停态完全不同 —— 直通映射到原型 `alarmRecords.handleStatus`。
6. **环境不可伪造**：环境由部署参数 `GK_ENV` 注入 + 凭证 `env_code` 二次校验，客户端不传环境。

## 四、数据模型总览（改版后 35 张表）

| 来源 | 数量 | 表名 |
|---|---|---|
| **新建** | 18 | `biz_line` `env` `sys_menu` `sys_role_menu` `sys_role_data_scope` `api_param` `api_version` `api_env_config` `api_change_log` `app_credential` `app_quota` `app_api_grant` `alarm_rule` `notify_channel` `sys_config` `sys_dict` `sys_dict_item` `block_rule` |
| **ALTER** | 10 | `app` `app_ip_whitelist` `api_group` `api_interface` `api_call_log` `ip_ban` `alert` `sys_user` `sys_role` `sys_operation_log`（+77 列、+21 索引） |
| **保持不变** | 8 | `app_rate_limit`（配额降级回退源）`app_api_permission`（授权回滚快照）`api_encryption_config` `app_encryption_config` `security_rule` `security_event` `export_task` `sys_user_role` |

种子数据：env 4 / biz_line 6 / sys_menu 54（6 模块 + 28 权限点 + 20 页面）/ dict 6 字典 22 项 / config 19 / notify_channel 4 / alarm_rule 7 / block_rule 5。

## 五、关键设计决策（7 条，架构师已出）

| # | 议题 | 决策 | 关键理由 |
|---|---|---|---|
| 1 | 环境维度 | `env_code` 冗余，不做 env_id 外键 | 网关 Redis key 零 JOIN；env_code 创建后不可修改保证一致性 |
| 2 | 灰度路由 | `VersionRouteHandler` 插 Permission 后，**appId 稳定 murmur 哈希**分流 | 随机分流会让同一应用在版本间跳变；支持 `X-GK-Version` 头强制指定 |
| 3 | 授权状态机 | **审批中一律不生效**（Default Deny，0待审批/4已驳回直接拒），过期双保险（Job 批量置 2 + 网关读时比时间） | 安全优先；过期 Job 延迟不会放过 |
| 4 | 数据权限 | MyBatis-Plus 3.5 内置 `DataPermissionInterceptor`，只对白名单表生效 | 不自研 SQL 拦截器；Dashboard / Job 用 `@InterceptorIgnore` 天然放行 |
| 5 | 菜单权限点 | `@RequirePerm("app:create")` 注解 + 拦截器 + Redis `gk:perm:{userId}` 权限集合 | **必须点名：当前只认证不授权，是 P0 安全补齐**；JWT 不带权限避免膨胀；权限变更主动 DEL 失效 |
| 6 | 告警 | **混合触发**：实时型（失败率/鉴权/QPS/延迟）走网关 Redis 滑窗 + 10s 评估；离线型（配额/密钥过期/僵尸）走 5min/1天 扫表 | 单触发方式无法兼顾实时与成本；埋点失败静默不阻断 |
| 7 | 字典/配置 | Cache-Aside + 写后主动 DEL（afterCommit 回调） | 事务回滚但缓存已删可控；多实例共享 Redis 无需 pub/sub；Redis 不可用时直连 DB + Caffeine 60s 兜底 |

## 六、Gap 与分期

| 批次 | 页面 | 新表 | 改造表 | 周期 | 验收 |
|---|---|---|---|---|---|
| **P0** | 10（概览、应用列表、接口分组、接口列表、接口授权总览、用户管理、业务线管理、环境与网关、字典管理、调用日志） | 8 | 6 | 5-6 周 | 6 条具体标准（见 §七） |
| **P1** | 9（角色管理、数据权限、操作审计、安全策略、参数配置、告警规则、通知渠道、告警记录、封禁管理） | 7 | 3 | 4-5 周 | 权限点全量生效、数据权限拦截器上线、告警闭环 |
| **P2** | 1 新页（接口参数/版本/环境配置/变更历史 合并页）+ 3 内嵌能力（审批流、灰度路由、按天分表、⌘K 全局搜索） | 3 | 1 | 3 周 | 端到端版本发布、⌘K 搜索可用 |

## 七、P0 验收标准（6 条，必须全部通过）

1. **环境隔离**：同应用 prod/test 两套凭证分别调用，test 通、prod 返 403 ACL
2. **接口资产完整**：能定义参数（含 JSON 导入）、配 4 套环境地址、连通测试置"已验证"
3. **授权矩阵**：按环境切换，10 秒内回答"哪些外部应用能调核心订单接口"
4. **调用日志可观测**：按 rejectStage 筛选，详情页可见拦截阶段+三段耗时（authCost/upstreamCost/totalCost）
5. **回归零退化**：现有 91 单测全绿 + 新增 4 类用例（多凭证鉴权/环境隔离/rejectStage/双层配额）
6. **存量数据完整**：迁移后现有应用 prod 调用行为零变化

## 八、实施路线（5 个任务，依赖图见架构 §8.4）

| 任务 | 内容 | 周期 | 关键产出 |
|---|---|---|---|
| **T01 数据层** | 真实 MySQL 跑 schema-v2.sql 与 migrate-v2.sql、生成 18 个 Entity、Mapper.xml 骨架 | 1 周 | 35 张表实机就绪、Entity/Mapper 通过 mybatis-plus 扫描 |
| **T02 权限基座** | `sys_menu`+`sys_role_menu` CRUD、`@RequirePerm` 注解 + 拦截器、Redis `gk:perm` 缓存、用户切换角色 | 1 周 | 11 个高危操作无法被普通用户访问 |
| **T03 接口/应用域** | 业务线/环境/应用凭证/接口参数/版本/环境配置/变更历史 + `bizLine/env/credential/apiParam/apiVersion/apiEnvConfig` 域 Controller | 1.5 周 | 应用列表/接口列表可看可改、新增凭证零停机轮换 |
| **T04 授权/告警/风控 + 网关** | `app_api_grant` 授权审批流 + 网关 `VersionRouteHandler` + 5 类安全检测埋点 + alarmRule 评估器 + notifyChannel 发送 | 1.5 周 | 授权矩阵生效、调用日志含 rejectStage+三段耗时 |
| **T05 前端 20 页** | 6 模块路由 + 菜单权限点驱动菜单 + 16 页改造 + 4 页新建 + `api/modules.js` 拆分 | 2 周 | 所有页面可点击可操作 |

**T01 启动条件已具备**：schema-v2.sql 已就位、远程 MySQL 192.168.132.143:13306 可达、迁移脚本全幂等、唯一前置是工程师实机跑一遍。

## 九、待决项与我的拍板

> 架构师列了 7 个待决，PM 列了 12 个，重叠去重后如下。**这些不影响 T01 启动**，但 P0 完成前需定。

| # | 议题 | 我的决定 | 理由 |
|---|---|---|---|
| Q1 | app/api/alert status 枚举语义 | **新增一列承载原型语义 + 存量列不动**（架构师 §3.1） | 保护 91 单测；alert.status 直通映射 alarmRecords.handleStatus |
| Q3 | 数据权限多角色取并集还是交集 | **并集**（PM 建议） | 符合 RBAC 直觉；交集太严苛用户难用 |
| Q7 | sys_config 热更新如何穿透 `@Value` 缓存 | **定义 `IGkConfig` 抽象 + 实现 `RedisGkConfig` + @Scheduled 兜底同步**；保留 `@Value` 用于启动期不变配置 | 不引入 Spring Cloud Config，复杂度可控；afterCommit 主动 DEL Redis |
| Q8 | 现有 5 类安全检测如何映射 alarmType | **新增 4 种**（权限越界 PERM_BREACH、异常入参 PARAM_INVALID、高频调用 HIGH_FREQ、异常时段 OFF_HOURS） | 原型 7 种加上现有 5 类，安全语义完整 |
| alarmType scopeType=1/2/3 含义 | 1=API / 2=APP / 3=IP | T04 实施时与 PM 对齐 |
| `dataScope=自定义` 的定义 | 落到 `sys_role_data_scope` 表，按 (role_id, scope_type, scope_value) 拆 | 满足个性化 |
| 是否要独立授权申请单表 | **P0 不做**，P2 做 `app_api_grant_audit` 留痕即可 | 申请人/审批人/理由已在 grants 表 |

## 十、起手动作（今天就做）

1. **T01 数据层启动**（已派工程师）：在真实 MySQL 上执行 schema-v2.sql + migrate-v2.sql，验证 35 张表就位、种子数据导入、迁移幂等。
2. **T01 同步做**：生成 18 个 Entity 类（MyBatis-Plus）、Mapper.xml 骨架、DTO 草案。
3. **运行现状**：后端 8080 + 前端 8081 仍在跑现有版本，**T01 不动现有 Controller 任何代码**，纯数据层叠加。
4. **Maven 单测保护**：T01 完成时跑全量 91 单测确认无回归（架构师保证 SQL 加列都是 `IF NOT EXISTS` 幂等）。

## 十一、关键风险

| 风险 | 缓释 |
|---|---|
| SQL 未实机执行验证（架构师自检） | T01 第一步就是实机跑，失败立即反馈不进入 T02 |
| 网关 `AppAuthHandler` 拆 5 子阶段可能引入路径变化影响日志表兼容 | 增量加列 (`reject_stage`、`auth_cost`)，存量行用 `''`/`0` 填充；告警/日志模块对历史 NULL 做空值降级 |
| 权限点全量生效后某些运维脚本被堵 | T02 收尾阶段列出所有 403 案例，紧急为运维账号加 `sys:admin` 角色 |
| schema-v2.sql 用 `DELIMITER` 存储过程 | 在 mysql CLI / Navicat / DataGrip 执行；Flyway 路径在文档已注明 |
| 接口 91 单测对启动期 yml 的密钥默认值依赖 | 现有默认值不与 schema 冲突，验证一次即可 |

---

**一句话回顾**：以「保留/改造/新建」三类边界为铁律，35 张表（18 新 + 10 改 + 8 不变）、22 个 Controller（12 保留 + 10 新）、20 个前端页面（16 改 + 4 增），分 P0/P1/P2 三期共 12-15 周完成。**今天开始 T01 数据层**。
