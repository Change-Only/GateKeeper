# APIM · 统一接口管理平台 重新设计 PRD

> 版本：v1.0·日期：2026-09-10·撰写：产品（PM）
> 输入来源：`docs/prototype/api-platform-原型.html`（APIM 交互原型，Vue2 + Element UI）+ 现有 GateKeeper 代码与库表（18 表/12 Controller）
> 文档定位：**可直接指导开发的规格说明**。所有字段、枚举值、业务规则均取自原型 MOCK 数据或现有代码；凡属推断的地方显式标注「（推断）」。
> 配套阅读：`docs/项目梳理-产品视角.md`、`docs/路线图-RICE优先级评分.md`、`docs/启动记录与缺陷修复.md`、`docs/优化方案-架构审查第1轮.md`

---

## 一、产品定位与版本目标

### 1.1 一句话定位

**APIM 是面向企业的 API 全生命周期管理平台**：以「接口资产 + 应用身份 + 授权关系」三张主数据为核心，把接口从"登记、版本演进、发布、授权、联调"到"运行观测、异常告警、封禁止损、合规审计"的完整生命周期收口在一处，网关只是它的执行面。

演进前（GateKeeper）：**API 集中权限管理与安全网关** —— 关键词是"管控"，回答"谁能调、能不能拦住"。
演进后（APIM）：**统一接口管理平台** —— 关键词是"资产 + 治理"，回答"有哪些接口、谁在调、调得怎么样、变更过什么、出了事怎么止损"。

> 安全能力不是被弱化，而是从"主角"退为"平台的一个保障面"：网关链路、5 类安全检测、封禁、审计全部保留，只是不再作为唯一卖点。

### 1.2 本次改版要解决的 5 个核心问题

| # | 问题（来自现有系统） | 根因 | 本次解法 |
|---|---|---|---|
| P1 | **接口只是"能转发的路径"**，没有参数、错误码、版本、SLA，接入方只能靠口口相传 | `api_interface` 只有 17 个字段，无参数定义 | 新增 `api_param`（含请求头/Body/响应/错误码四类 + 树形嵌套 + 敏感标记）、`api_version`、`api_env_config`、`api_change_log`；提供 JSON 示例一键生成结构 + 在线文档 |
| P2 | **单环境单套密钥**，dev/test/pre/prod 混用，`reset-secret` 是唯一轮换手段且中断业务 | `app` 表内嵌 `app_key`/`app_secret` 单值 | 拆出 `app_credential` 多套凭证（alias/envCode/expireTime/rotateFlag），支持"新建→灰度切换→吊销旧密钥"平滑轮换；Secret 仅创建时展示一次 |
| P3 | **授权无边界**：一条 `(app_id, interface_id, status)` 记录，没有环境、没有配额、没有有效期、没有理由、没有审批 | `app_api_permission` 仅 6 个业务字段 | 授权升级为 env 维度 + 双层配额（应用级 + 授权级）+ 有效期 + 五态状态机 + 申请人/审批人/理由；提供 App×API 授权矩阵 |
| P4 | **管不了"谁能看什么"**：所有后台登录用户看到同样的数据；`sys_role` 只是一个名字，没有权限点 | 无菜单/权限点表，无数据权限 | 29 个权限点（`permCode`，11 个高危红标）+ 角色-权限点授权 + 三维数据权限（业务线/环境/接口分组），**服务端 MyBatis 拦截器强制**，前端隐藏只是体验 |
| P5 | **配置靠改 yml 重启，告警只有站内**：`gatekeeper.*` 全部硬编码在 `application.yml`；告警无规则、无渠道、无静默期 | 无 `sys_config`/`sys_dict`/`alarm_rule`/`notify_channel` 表 | 新增参数配置（4 分组运行时可改）、字典、告警规则（类型/阈值/窗口/级别/静默期）、通知渠道（企微/钉钉/邮件/Webhook + 连通性测试） |

### 1.3 版本目标（可度量）

| 指标 | 现状 | 目标 |
|---|---|---|
| 接口资产完整度（有参数定义的已发布接口占比） | 0% | ≥ 80%（P0 结束） |
| 授权记录带环境与有效期的比例 | 0% | 100%（P0 结束） |
| 应用凭证平均轮换停机时长 | 全量中断（reset-secret） | 0（多密钥平滑轮换，P0 结束） |
| 后台权限点可配置 | 不支持 | 29 个点全可配（P1 结束） |
| 告警投递渠道 | 站内 1 种 | ≥ 3 种（P1 结束） |
| "哪些外部应用能调核心订单接口"的查询耗时 | 需人工翻表 | ≤ 10 秒（授权矩阵，P0 结束） |

### 1.4 明确不做（Scope Out）

- 不做 API 网关的流量编排、服务编排、协议转换（非 APIM 定位）
- 不推翻现有网关责任链、5 类安全检测、百万级异步导出、Redis fail-open 降级——**只做补齐与融合**
- 不做多租户 SaaS 化（本版本单企业内多业务线）
- 不做 API 计费/账单

---

## 二、角色与视角

### 2.1 五种角色定义（取自原型 `MOCK.roles`）

| 角色编码 | 角色名称 | 角色类型 | 数据范围 dataScope | 核心诉求 | 典型来源 |
|---|---|---|---|---|---|
| `ADMIN` | 平台管理员 | 1 内置 | 全部数据 | 全平台管控、看态势、接告警、改安全策略 | 运维/安全/平台团队 |
| `BIZ_ADMIN` | 业务线管理员 | 1 内置 | 仅本业务线 | 管理本业务线内的应用与接口、审批授权 | 各业务线负责人 |
| `API_PROVIDER` | 接口提供方（≈ 开发者） | 1 内置 | 自定义 | 登记与发布接口、定参数/版本/SLA、看自己接口的调用情况 | 后端开发 |
| `API_CONSUMER` | 接口调用方（≈ 应用负责人） | 1 内置 | 仅本人 | 管理自己的应用与密钥、申请授权、查自己的调用日志 | 调用方开发 |
| `AUDITOR` | 审计员（≈ 安全审计员） | 1 内置 | 全部数据 | 全局只读 + 审计日志导出 + 处理告警 | 安全合规部 |
| `EXTERNAL_PM` | 外部对接人 | 2 外部 | 仅本人 | 仅查看自己被授权的应用与调用情况 | 外部合作方 |

> 角色类型 `roleType`：1=内置（列表显示 info 色「内置」标签）/ 2=外部（原型未渲染标签，推断为「外部」）。
> 数据范围 `dataScope` 为**字符串非数字**：`全部数据`/`仅本业务线`/`自定义`/`仅本人`，前端原样渲染。

> 映射说明：「业务线负责人」= `BIZ_ADMIN`；「应用负责人」= `API_CONSUMER`；「安全审计员」= `AUDITOR`；「开发者」= `API_PROVIDER`（接口提供方）。原型顶栏「视角」下拉 5 项不含外部对接人（后者通过登录账号角色自然生效）。

### 2.2 角色 × 权限点矩阵（取自原型 `MOCK.menus` + `MOCK.rolePerms`，共 29 个权限点）

权限点清单（`permCode`/高危标记 risk=1）：

| 模块 | 权限点 |
|---|---|
| 概览(1) | `dashboard:view` |
| 应用管理(7) | `app:list`、`app:create`、`app:update`、`app:disable`🔴、`app:credential:create`🔴、`app:credential:reset`🔴、`app:credential:revoke`🔴 |
| 接口管理(6) | `api:list`、`api:create`、`api:update`、`api:publish`🔴、`api:offline`🔴、`api:env:update` |
| 权限管理(7) | `grant:list`、`grant:create`、`grant:approve`、`grant:revoke`🔴、`sys:user:update`🔴、`sys:role:grant`🔴、`audit:export`🔴 |
| 系统设置(5) | `sys:config:list`、`sys:config:update`、`sys:security:update`🔴、`sys:dict:update`、`sys:alarm:update` |
| 监控与审计(3) | `log:call:list`、`log:call:detail`、`alarm:handle` |

> 高危权限点共 **11 个**（🔴）。规则：**高危操作必须二次确认 + 强制填写原因 + 写审计日志 riskFlag=1 + 实时通知所有平台管理员**。

角色授权（`MOCK.rolePerms`，数字为权限点 id）：

| 角色 | 权限点数 | 授权范围 | 显式排除 |
|---|---|---|---|
| ADMIN 平台管理员 | 29/29 | 全部 | — |
| BIZ_ADMIN 业务线管理员 | 24/29 | 应用/接口/授权/监控全操作 + 查看配置 + 字典 + 告警配置 | `sys:config:update`、`sys:security:update`、`sys:user:update`、`sys:role:grant`、`audit:export` |
| API_PROVIDER 接口提供方 | 13/29 | 接口全生命周期（含发布/下线）+ 查看应用 + 授权查看/新增/审批 + 日志查看 | 应用写操作、凭证操作、撤销授权、系统设置、告警处理 |
| API_CONSUMER 接口调用方 | 9/29 | 应用新建/编辑 + 密钥创建 + 接口查看 + 授权查看 + 日志查看 | 接口写、审批、撤销、所有系统设置 |
| AUDITOR 审计员 | 9/29 | 全局只读（概览/应用/接口/授权/配置查看/日志）+ `alarm:handle` + `audit:export` | 一切写操作（除告警处理与审计导出） |
| EXTERNAL_PM 外部对接人 | 6/29 | 概览 + 查看应用 + 创建密钥 + 查看授权 + 日志查看 | 其余全部 |

### 2.3 「视角」切换设计

**原型的局限**：原型 `switchRole` 只弹通知、未真正改变菜单与数据，**不能作为实现方案**。

**正式实现语义（推断并确认）**：

1. 视角下拉是**当前生效角色**切换器（非"模拟"权限）；鉴权边界始终由服务端按"用户全部角色并集 + 当前生效角色"裁决，前端切换只影响展示、不影响鉴权结果。
2. **下拉项 = 当前登录用户实际拥有的角色**（`sys_user_role` 查得）；无的角色不可见不可选，单角色用户下拉置灰并提示"当前仅一个角色"。
3. 切换后立即生效的三件事：
   - **菜单**：按当前角色的权限点集合渲染 `MENU_TREE`（无权限的菜单项直接不渲染，不是置灰）
   - **数据范围**：所有列表/详情/导出请求携带 `X-Active-Role` 头（推断），服务端按该角色的 `data_scope` 配置过滤
   - **操作按钮**：无权限点的操作按钮不渲染（如 API_CONSUMER 看不到"发布接口"）
4. 切换后**不重新加载页面**，仅刷新当前页数据；若当前页在新角色下无权限，自动跳转概览并提示。
5. 切换行为本身写审计日志（`module=ROLE`, `operateType=SWITCH`, `riskFlag=0`）（推断）。

### 2.4 ⌘K 全局搜索设计

原型顶栏有搜索框（placeholder「搜索接口、应用、日志…」+ `⌘K` 角标），未实现。设计如下：

| 项 | 规格 |
|---|---|
| 触发 | `Cmd+K`（macOS）/ `Ctrl+K`（Win）聚焦并展开下拉面板；`Esc` 关闭；`↑`/`↓` 选择，`Enter` 跳转 |
| 搜索范围（4 类） | ① **接口**：`apiName`/`apiCode`/`path` 模糊匹配<br>② **应用**：`appName`/`appCode` 模糊匹配<br>③ **业务线**：`lineName`（跳转「接口列表」并按业务线过滤）<br>④ **调用日志**：`traceId` **精确匹配** |
| 结果呈现 | 按类别分组，每组最多 10 条，共 ≤ 40 条；每条显示 主标题 + 副标题（接口显示 `METHOD path`，应用显示 `appCode`） |
| 跳转目标 | 接口 → 接口详情抽屉（`api-list` 页）；应用 → 应用详情抽屉（`app-list` 页）；日志 → 调用链路详情抽屉（`mon-calllog` 页）；业务线 → `api-list` 页带过滤 |
| 权限 | **结果必须过数据权限过滤**——搜到了但无权限的对象直接不返回，不做"无权限"占位提示（避免探测） |
| 实现 | P1：MySQL `LIKE 'kw%'` 前缀匹配 + 各类 `LIMIT 10`（接口/应用走本地缓存，日志走 `traceId` 唯一索引）<br>P2：日志检索接 ES 后可放开全文 |
| 防抖 | 输入防抖 300ms，最短关键词 2 字符 |

### 2.5 各角色关注点与可见范围

| 角色 | 首页默认落地页 | 概览页看到什么 | 主要活动页面 | 不可见 |
|---|---|---|---|---|
| 平台管理员 | 概览 | 全量 5 指标 + 全部待办 + 全部风险 | 全部 20 页 | — |
| 业务线管理员 | 概览（本业务线口径） | 本业务线指标、本业务线待办与风险 | 应用列表、接口分组/列表、接口授权总览、环境与网关、告警规则、通知渠道、调用日志、告警记录 | 用户管理、角色管理、审计导出、安全策略修改、参数配置修改 |
| 接口提供方 | 接口列表 | 本业务线指标 + 与"我负责的接口"相关的待办（待审核接口） | 接口分组、接口列表（含参数/版本/环境/文档/变更）、授权情况、调用日志 | 应用写操作、凭证操作、系统设置、封禁 |
| 应用负责人 | 应用列表 | 自己应用指标 + 自己的待审批授权/密钥过期 | 应用列表（密钥/授权/配额/IP）、接口列表（只读）、接口授权总览（只读）、调用日志 | 接口写、审批、系统设置、审计 |
| 安全审计员 | 概览 | 全量只读 + 未处理告警数 | 全部页面（只读）、操作审计、告警记录、封禁管理（只读） | 一切写操作（除告警处理与审计导出） |
| 外部对接人 | 应用列表（仅自己的） | 仅自己应用的调用量/成功率 | 应用列表（自己的应用：密钥 + 授权 + 调用日志） | 其余全部 |

---

## 三、现状 Gap 分析表

**统计：共 35 项 —— 需新建 17 项/需改造 12 项/已有沿用 6 项。**

图例：🆕 需新建·🔧 需改造·✅ 已有沿用

### A. 组织与运行环境

| # | 域 | 原型要求（字段/规则） | 现有系统能力 | 结论 | 落地要点 |
|---|---|---|---|---|---|
| A1 | 业务线 | `biz_line`: id/lineCode/lineName/ownerName/memberCount/status/remark | 无。业务线概念完全不存在，`api_group` 只有 `parent_id` 树 | 🆕 新建 | 新建 `biz_line` 表；`api_group`、`api_interface`、`app` 增加 `line_id`。初始化 5 条业务线（trade/pay/user/data/supply）。**数据权限的第一维度** |
| A2 | 环境 | `env`: id/envCode(dev,test,pre,prod)/envName/gatewayUrl/sort/status | 无。环境概念不存在，接口只有单一 `backend_url`，IP 白名单无环境 | 🆕 新建 | 新建 `env` 表；`app.env_scope`(JSON数组)、`app_credential.env_code`、`app_quota.env_code`、`app_ip_whitelist.env_code`、`api_env_config.env_code`、`grant.env_code` 全部补环境维度 |

### B. 接口资产域

| # | 域 | 原型要求 | 现有系统能力 | 结论 | 落地要点 |
|---|---|---|---|---|---|
| B1 | 接口分组元数据 | `api_group`: groupCode/groupName/lineId/lineName/ownerName/apiCount/sort/status/remark | `api_group`: id/group_name/parent_id/description/sort_order（9 字段） | 🔧 需改造 | 加 `group_code`(唯一、创建后不可改)、`line_id`、`owner`、`status`；`parent_id` 与原型冲突——原型分组是**扁平**的（无父子），建议保留 `parent_id` 字段但 P0 不启用（推断） |
| B2 | 接口元数据 | `api`: apiCode/apiName/groupId/lineId/path/method/visibility(1内部,2对外)/authRequired/ownerName/status(0草稿,1待审核,2已发布,3已弃用,4已下线)/version/grantCount/call24h/successRate/avgCost/tags/sla/desc/transportSecurity(NONE,TLS,MTLS) | `api_interface`: interface_name/interface_path/request_method/request_param_type/group_id/backend_url/status/timeout_ms/description | 🔧 需改造 | 加 `api_code`、`line_id`、`visibility`、`auth_required`、`current_version`、`sla`、`tags`(JSON)、`owner`、`transport_security`、`success_rate`/`call_24h`/`avg_cost`（统计派生，走预聚合表）；`backend_url`/`timeout_ms` **迁移到 `api_env_config`** 后废弃 |
| B3 | 接口参数定义 | `api_param`: id/apiId/paramType(1请求头,3Body字段,4响应字段,5错误码)/parentId(树形嵌套)/fieldName/fieldType(string,int,number,array,object)/required/example/desc/sensitive/encryptRule(NONE,SYMMETRIC,MASK)/errorCode/httpStatus | 无。`api_interface.request_param_type` 仅一个枚举值 | 🆕 新建 | 新建 `api_param` 表 + 树形展示（`parent_id` 自关联）。**提供「从 JSON 示例生成结构」**：解析递归仅展开 object，array 不递归（与原型 `walk()` 逻辑一致）。敏感字段 + encryptRule 是脱敏/加密执行的依据 |
| B4 | 接口版本与灰度 | `api_version`: id/apiId/version(v1,v2)/status(0开发中,1已发布,2已弃用,3已下线)/isCurrent/grayRatio/deprecateTime/offlinePlanTime/changeLog | 无 | 🆕 新建 | 新建 `api_version`。**注意：版本状态枚举（0开发中/1已发布/2已弃用/3已下线）与接口状态枚举（0草稿/1待审核/2已发布/3已弃用/4已下线）是两套，不要复用同一个字典** |
| B5 | 接口环境配置 | `api_env_config`: id/apiId/envCode/upstreamUrl/connectTimeout/readTimeout/retryCount/mockEnabled/configStatus(0未配置,1已配置,2已验证) | 全局单值：`api_interface.backend_url` + `api_interface.timeout_ms` | 🆕 新建 | 新建 `api_env_config`（4 环境 × 每接口）。**数据迁移**：把现有 `backend_url`/`timeout_ms` 写入 `env_code='prod'` 一条，其余环境标记"未配置"。提供「连通测试」按钮 → 成功置 `configStatus=2` |
| B6 | 接口变更历史 | `api_change_log`: id/apiId/changeType(CREATE,UPDATE,PUBLISH)/fieldName/fieldLabel/oldValue/newValue/operatorName/createTime/changeReason | 无接口级变更史；只有通用 `sys_operation_log`（无 old/new value） | 🆕 新建 | 新建 `api_change_log`。接口元数据/环境配置/参数定义的每次变更写一条，变更原因必填。与 `sys_operation_log` 区别：后者记"操作动作"，前者记"字段级 diff" |
| B7 | 在线文档/导入 | 接口详情「在线文档」Tab：请求参数/响应字段/错误码/curl 示例（含 `X-Api-AppKey`/`Timestamp`/`Nonce`/`Signature` 四个头）；「Swagger 导入」按钮；JSON 示例解析 | 仅有 Knife4j（管理端 API 文档，非业务接口文档） | 🆕 新建 | P0 先做文档渲染（读 `api_param` 直接渲染）；Swagger/OpenAPI 导入放 P2 |

### C. 应用域

| # | 域 | 原型要求 | 现有系统能力 | 结论 | 落地要点 |
|---|---|---|---|---|---|
| C1 | 应用元数据 | `app`: appCode/appName/lineId/appType(1内部,2外部,3测试)/envScope(数组)/ownerName/contactName/contactInfo/approvalRequired/status(0待审核,1已启用,2已禁用,3已注销)/credCount/grantCount/call24h/trend/description/createTime | `app`: app_name/app_key/app_secret/status/description/expire_time | 🔧 需改造 | 加 `app_code`(唯一)、`line_id`、`app_type`、`env_scope`(JSON)、`owner`、`contact_name`/`contact_info`、`approval_required`。status 枚举需从现有（推测 0/1）扩展为 0待审核/1已启用/2已禁用/3已注销，**需确认现有 status 语义并做映射**（推断） |
| C2 | 多套凭证 + 轮换 | `app_credential`: id/appId/appKey/secretMask/alias/envCode/status(1启用中,2已停用,3已吊销,4已过期)/expireTime/lastUsedTime/lastUsedIp/rotateFlag/createTime。Secret 仅创建时展示一次，支持多套并存平滑轮换 | `app` 表内嵌 `app_key`/`app_secret` **单套**；仅 `POST /app/{id}/reset-secret`（重置即中断） | 🔧 需改造 | 拆表 `app_credential`。**关键**：一套应用可有多套凭证，新旧并存（`rotateFlag=1` 标记"轮换中(新)"），网关鉴权按 `appKey` 查 `app_credential`（不是查 `app`）。`app_secret` 落库仍用现有 AES-256-GCM 可逆加密（`GATEKEEPER_AES_KEY`）。`AppAuthHandler` 与 `App` 缓存结构需同步改造 |
| C3 | 应用配额 | `app_quota`: id/appId/envCode/globalQps/dailyQuota/monthlyQuota/concurrency | `app_rate_limit`: app_id/qps_limit/concurrent_limit/daily_limit（**无 env、无 monthly**） | 🔧 需改造 | 加 `env_code`、`monthly_quota`，改名为 `app_quota`（或保留表名加列，建议加列减少风险）。**双层设计**：应用级（本表）保护整体，授权级（`grant`）做精细化控制，两级任一命中返回 429 |
| C4 | IP 白名单 | `app_ip_whitelist`: id/appId/ipValue(CIDR)/envCode/remark/createTime。外部合作方（`appType=2`）**强制**要求配置 | `app_ip_whitelist`: app_id/ip_cidr/remark（**无 env**） | 🔧 需改造 | 加 `env_code`。存量数据默认归入 `prod`（推断，需确认）。新增校验：`app_type=2` 且白名单为空 → 应用详情页红色告警条（原型原话"为空则存在安全风险"）+ 配置项 `external.ip.whitelist.required=true` 时阻断启用 |

### D. 授权域

| # | 域 | 原型要求 | 现有系统能力 | 结论 | 落地要点 |
|---|---|---|---|---|---|
| D1 | 授权记录增强 | `grant`: id/appId/apiId/**envCode**/qpsLimit/dailyQuota/usedToday/status(0待审批,1已生效,2已过期,3已撤销,4已驳回)/validFrom/validTo/grantReason/applicantName/auditorName/auditTime | `app_api_permission`: id/app_id/interface_id/status/created_at/updated_at（**6 字段，无环境/配额/有效期/人/理由**） | 🔧 需改造 | 扩展 `app_api_permission`（建议更名 `app_api_grant`）：加 `env_code`、`qps_limit`、`daily_quota`、`used_today`、`valid_from`、`valid_to`、`grant_reason`、`applicant_id`/`applicant_name`、`auditor_id`/`auditor_name`、`audit_time`。**唯一键从 `(app_id, interface_id)` 变为 `(app_id, interface_id, env_code)`** |
| D2 | 授权审批流 | 五态状态机：待审批 → 已生效/已驳回；已生效 → 已过期（到期自动）/ 已撤销（人工）。申请人、审批人、审批时间、理由全留痕。开关 `approval.enabled`（原型默认 `false`，"MVP 关闭，V2 开启"） | 无审批。`POST /permission` 直接生效；`POST /permission/batch`、`/grant-by-group` 批量生效 | 🆕 新建 | P0 只建状态机与字段，**审批流默认关闭**（`approval.enabled=false`，提交即生效并记录 `applicantName`，`auditorName` 留空）；P2 开启后需审批人路由（接口负责人/业务线管理员，推断） |
| D3 | 授权矩阵视图 | App × API 授权矩阵：行=已启用应用、列=已发布接口、单元格=状态 + 日配额使用率；支持按环境切换、仅看外部应用、导出 Excel | `GET /permission/list` 仅列表；无矩阵、无按环境切换 | 🆕 新建 | 新增矩阵聚合接口。**性能**：接口列建议 ≤ 50 条（超过时提示收窄分组），一次查询 `(应用数 × 接口数)` 条授权关系，走 `(app_id, interface_id, env_code)` 唯一索引批量查，禁止 N+1 |

### E. 控制面 RBAC

| # | 域 | 原型要求 | 现有系统能力 | 结论 | 落地要点 |
|---|---|---|---|---|---|
| E1 | 用户元数据 | `user`: username/realName/empNo/email/mobile(脱敏 `138****0001`)/dept/lineName/roles(多角色)/status/lastLoginTime/lastLoginIp | `sys_user`: username/password/real_name/email/phone/status/last_login_at/last_login_ip | 🔧 需改造 | 加 `emp_no`、`dept`、`line_id`。手机号**列表展示即脱敏**（与 `log.desensitize` 规则一致）。多角色已由 `sys_user_role` 支持 ✓ |
| E2 | 角色元数据 | `role`: roleCode/roleName/roleType(1内置,2外部)/dataScope(全部数据/仅本业务线/自定义/仅本人)/userCount/status/remark | `sys_role`: role_name/role_code/description/status | 🔧 需改造 | 加 `role_type`、`data_scope`。初始化 6 个角色（ADMIN/BIZ_ADMIN/API_PROVIDER/API_CONSUMER/AUDITOR/EXTERNAL_PM），`role_type=1` 内置不可删 |
| E3 | 菜单权限点 | `menu`: 6 模块 × 29 权限点（`permCode` + `risk` 高危标记 + 树形 pid） + `role_menu` 关联。权限粒度到「操作」而非「菜单」；高危操作点单独授权 + 二次确认 + 实时通知 | 无。现有 `JwtAuthInterceptor` 只校验"登录态"，**不校验任何权限点**（所有登录用户等价） | 🆕 新建 | 新建 `sys_menu` + `sys_role_menu`。**这是本次最大的安全补齐项**。拦截器需从"是否登录"升级为"是否持有 `permCode`"，建议注解化（如 `@RequirePerm("app:credential:revoke")`，推断）。保存角色权限后**清除该角色下所有在线用户的权限缓存**（原型原话） |
| E4 | 数据权限 | 三维：业务线（多选）/ 环境（多选）/ 接口分组（多选，不限留空）。带「效果预览」。**服务端强制（MyBatis 拦截器自动拼 SQL 条件）** | 无。所有用户看到同样数据 | 🆕 新建 | 新建 `sys_role_data_scope`。实现 MyBatis 拦截器，对 `app`/`api_interface`/`api_group`/`api_call_log`/`grant` 等表自动追加过滤条件（详见第六节）。原型原话："前端隐藏菜单只是体验优化，不是安全边界" |

### F. 系统设置

| # | 域 | 原型要求 | 现有系统能力 | 结论 | 落地要点 |
|---|---|---|---|---|---|
| F1 | 参数配置 | `config`: configKey/configValue/configGroup(SECURITY,GATEWAY,LOG,DEFAULT)/configName/sensitive/builtIn/remark。运行时可改，18 条内置配置 | 无表。全部硬编码在 `application.yml` 的 `gatekeeper.*`（crypto/jwt/cors/gateway/security/log/export）+ `@Value` 注入 | 🆕 新建 | 新建 `sys_config`。**注意**：`GATEKEEPER_AES_KEY`/`JWT_SECRET` 等启动密钥**不入库**（`SecurityStartupCheck` 强制环境变量注入，保持现状）；只有运行时可调项（超时、阈值、开关、保留期）入库。热更新需广播清除 `@Value` 缓存（建议改走配置中心式读取，推断） |
| F2 | 字典 | `dict`: dictCode/dictName/builtIn/status/items[{itemValue, itemLabel, sort, status}]。6 个内置字典：app_type/visibility/api_status/grant_status/cred_status/alarm_level。规则：**item_value 被程序引用后不可改，只能改显示名和停用** | 无。枚举散落在 Java 常量/前端硬编码 | 🆕 新建 | 新建 `sys_dict` + `sys_dict_item`。前后端枚举统一从字典读；P0 先保证 6 个内置字典可用，自定义字典 P1 |
| F3 | 安全策略页 | SECURITY 分组 11 条配置的独立页面；修改需填**变更原因 ≥10 字符** + 勾选"已知晓风险" + 写审计 + **实时通知所有平台管理员**；附签名规范速查（StringToSign 7 行 + 4 个请求头） | 无对应页面；`SecurityStartupCheck` 仅启动时校验密钥强度 | 🆕 新建 | 新建页面 + 变更审批交互。签名规范速查为静态说明（HmacSHA256，`Signature = Base64(HMAC-SHA256(StringToSign, Secret))`），与现有 `AppAuthHandler` 实现一致性需研发核对 |
| F4 | 告警规则 | `alarm_rule`: ruleName/alarmType(FAIL_RATE,AVG_LATENCY,QPS_SURGE,QUOTA_USAGE,AUTH_FAIL,CERT_EXPIRE,KEY_EXPIRE,ZOMBIE_API)/scopeType/threshold/timeWindow(分钟)/alarmLevel(1提示,2警告,3严重)/silencePeriod(分钟)/channelNames/receiverNames/status | `security_rule`: rule_name/rule_type/rule_config/trigger_action/ban_duration_min/enabled/description —— 这是**安全检测/封禁规则**，不是告警规则；无阈值/窗口/级别/静默期/渠道/接收人概念 | 🆕 新建 | 新建 `alarm_rule` + `alarm_rule_channel` + `alarm_rule_receiver`。**不要复用 `security_rule`**（语义不同：`security_rule` 是"检测到就封"，`alarm_rule` 是"统计超阈值就通知"）。初始化 7 条规则（原型 MOCK） |
| F5 | 通知渠道 | `notify_channel`: channelName/channelType(WECOM,DINGTALK,EMAIL,WEBHOOK)/status/lastTestTime/lastTestResult。支持「发送测试」 | 无。告警仅站内（`alert` 表 + 前端红点） | 🆕 新建 | 新建 `notify_channel` + 4 个发送器。RICE 路线图已排此项（RICE 3.6，Next 批次），本次提前到 P1。失败重试 + 记录 `last_test_result` |
| F6 | 日志与审计设置 | 保留期：调用日志热数据(1-90天)/冷归档(30-730天)/操作审计(≥180天)；脱敏规则开关 + 字段（手机号/身份证号/银行卡号/邮箱/姓名）；容量策略说明 + 4 个定时任务清单 | 仅 `gatekeeper.log.retention-days: 90`（调用日志），单表无冷热分层；无脱敏规则页面 | 🆕 新建 | 新建页面 + 落 `sys_config`（`call.log.hot.days=30`、`audit.log.retention.days=180`）。**注意冲突**：现有 retention-days=90 vs 原型热数据 30 天，需统一口径（建议：热 30 天 → 冷 180 天 → 删除，总计 180 天；等保三级审计 ≥180 天）。脱敏规则落 `sys_config` 或独立表（推断） |

### G. 监控与审计

| # | 域 | 原型要求 | 现有系统能力 | 结论 | 落地要点 |
|---|---|---|---|---|---|
| G1 | 调用日志增强 | `call_log`: traceId/appName/apiName/path/method/**envCode**/httpStatus/**errorCode**/**rejectStage**(TIMESTAMP,NONCE,APPKEY,SIGNATURE,IP,ACL,RATELIMIT,UPSTREAM)/clientIp/**totalCost**/**authCost**/**upstreamCost**/requestBody/responseBody | `api_call_log`: app_id/app_name/interface_id/interface_path/request_method/request_time/request_params/response_data/response_status/cost_time/client_ip/encryption_algorithm/is_rate_limited/is_blocked/block_reason | 🔧 需改造 | 加 `trace_id`、`env_code`、`error_code`、`reject_stage`、`auth_cost`、`upstream_cost`（`cost_time` 复用为 totalCost）。**`reject_stage` 是核心价值**：需把现有 `AppAuthHandler` 内部拆分为 时间戳/Nonce/AppKey/状态/签名 5 个子阶段并各自记录拒绝原因（现有只在整体失败时写 `block_reason`）。详情抽屉需展示"各阶段耗时"三段 |
| G2 | 告警记录增强 | `alarm_record`: ruleId/ruleName/alarmLevel/alarmContent/scopeDesc/triggerValue/handleStatus(0未处理,1处理中,2已处理,3已忽略)/handlerName/handleTime/handleRemark/createTime | 分裂为两张表：`alert`（title/level/source/content/status/handle_remark，无规则关联、无触发值、无处理中态）+ `security_event`（event_type/event_desc/trigger_rule/handle_status，是安全事件而非告警） | 🔧 需改造 | 建议统一为 `alarm_record`：加 `rule_id`、`scope_desc`、`trigger_value`、`handler_name`、`handle_time`；`handle_status` 扩为 4 态（现有 alert.status 语义需映射，推断）。`security_event` 保留为安全事件原始流水（作为告警的数据源之一），不再直接面向用户展示 |
| G3 | 封禁管理增强 | `blocklist`: scope(IP,APP,PLATFORM)/target/envCode/reasonCode/reasonDetail/blockType(AUTO,MANUAL)/blockTimes/ttlSeconds/status(1生效中,0已解封)/expireTime/blockBy/unblockBy/unblockReason/unblockTime。+ `block_rule` 5 条（REPLAY_ATTACK/SIGNATURE_MISMATCH/RATE_LIMIT_EXCEEDED/IP_NOT_ALLOWED/MANUAL）。**生产默认 `blocklist.auto.enabled=false`，所有 auto 规则 enabled=false** | `ip_ban`: ip_address/app_id/ban_reason/ban_start_time/ban_end_time/ban_status/ban_type —— **仅 IP 维度**，无 scope 枚举、无 reasonCode、无解封人/解封原因留痕。`security_rule` 承担规则角色但字段不匹配 | 🔧 需改造 | 扩展 `ip_ban` → 建议改名 `blocklist`：加 `scope`、`reason_code`、`ttl_seconds`、`block_times`、`unblock_by`、`unblock_reason`、`unblock_time`；`ip_address` 泛化为 `target`（可存 IP/CIDR/AppKey）。新增 `block_rule` 5 条初始化数据。**默认全关**（与现有 `perm-breach-auto-ban: false` 一致，理念吻合） |

### H. 现有有而原型未独立呈现（保留 / 融合）

| # | 能力 | 现状 | 原型中的落点 | 结论 | 落地要点 |
|---|---|---|---|---|---|
| H1 | 加解密管理（SM2/SM3/SM4 + AES，接口级 + 应用级独立配置） | ✅ 已有：`api_encryption_config`(19字段)/`app_encryption_config`(15字段)/`EncryptionConfigController`/`EncryptionHandler` | 原型**没有独立模块**，而是下沉为接口参数的 `sensitive` + `encryptRule`(NONE/SYMMETRIC/MASK) | ✅ 保留 + 融合 | **不删现有模块**。把 `encryptRule` 作为"声明层"（在参数定义上标哪些字段要加密/脱敏），现有 `*_encryption_config` 作为"执行层"（算法/密钥/模式）。接口详情页「敏感字段」描述项展示 `sensitiveCount` 并链接到加密配置 |
| H2 | 5 类安全检测（鉴权失败/权限越界/异常入参/高频调用/异常时段） | ✅ 已有：`AbnormalParamCheckHandler` + `security_rule` + `security_event`，配置项齐全（`auth-fail-threshold`、`high-freq-qps`、`off-hours-start/end`、`perm-breach-*`） | 映射到告警类型 `AUTH_FAIL`（鉴权失败）、`ZOMBIE_API`（僵尸接口）等；其余类型原型未覆盖 | ✅ 保留 + 融合 | 现有 5 类检测继续产出 `security_event`，同时作为 `alarm_rule` 的数据源（新增 `PERM_BREACH`、`ABNORMAL_PARAM`、`HIGH_FREQ`、`OFF_HOURS` 四种 alarmType，推断——原型只列了 8 种，未包含这 4 种，需补充） |
| H3 | 百万级异步导出中心 | ✅ 已有：`export_task` 表 + 分批流式 + 临时文件原子改名 + 有界线程池背压 | 原型在调用日志/操作审计/授权矩阵页都有「导出」按钮 | ✅ 沿用 | 零改造，新页面的导出按钮复用现有导出中心（`POST /log/export` 模式） |
| H4 | Redis fail-open 降级 + 健康监控 | ✅ 已有：防护组件失效不拖垮业务链路 | 原型未显式描述 | ✅ 沿用 + 显式化 | 在「环境与网关」页的鉴权链路说明中补一句降级策略；新增环境维度后，封禁/限流的 Redis key 需带 `env_code` 前缀（推断） |
| H5 | 网关责任链（10 Handler） | ✅ 已有：`AppAuth → IpBan → IpWhitelist → RateLimit → Permission → AbnormalParam → Encryption → Forward → Log`（按 `@Order`） | 原型 10 步：时间戳容差 → Nonce去重 → AppKey查证 → 应用状态校验 → 签名比对 → IP白名单 → 接口ACL → 配额限流 → 转发后端 → 异步日志 | ✅ 保留，⚠ 顺序差异 | 差异两处：① 现有 IP 白名单在签名**之前**，原型在签名**之后**；② 原型把认证拆为 5 个细分阶段（现有合并为 1 个 Handler）。**「配额消耗必须在身份确认之后」这一原则两边一致** ✓。建议 P2 对齐（低风险低收益），P0 保持现状 |
| H6 | 数据大屏 `/dashboard/screen/*` | ✅ 已有：overview/trend/app-rank/interface-rank/recent-events | 原型「概览」页：5 指标卡 + 待办 + 风险提示 + 双趋势图 + Top 接口/Top 应用 | ✅ 融合 | 新概览页**复用现有 5 个大屏接口**，前端重组为原型布局；新增「待办事项」「风险提示」两个聚合接口（P0 新增，数据来源：待审核接口数/待审批授权数/待审核应用数/30天内到期密钥数/未处理告警数） |

### Gap 汇总

| 结论 | 数量 | 项 |
|---|---|---|
| 🆕 需新建 | **17** | A1 业务线、A2 环境、B3 参数定义、B4 版本灰度、B5 环境配置、B6 变更历史、B7 在线文档/导入、D2 审批流、D3 授权矩阵、E3 菜单权限点、E4 数据权限、F1 参数配置、F2 字典、F3 安全策略页、F4 告警规则、F5 通知渠道、F6 日志审计设置 |
| 🔧 需改造 | **12** | B1 接口分组、B2 接口元数据、C1 应用元数据、C2 多套凭证、C3 应用配额、C4 IP白名单(env)、D1 授权记录、E1 用户、E2 角色、G1 调用日志、G2 告警记录、G3 封禁管理 |
| ✅ 已有沿用 | **6** | H1 加解密管理、H2 5类安全检测、H3 异步导出中心、H4 Redis fail-open、H5 网关责任链、H6 数据大屏 |

**新增表预估（17 项新建对应的物理表）**：`biz_line`、`env`、`api_param`、`api_version`、`api_env_config`、`api_change_log`、`app_credential`、`alarm_rule`、`alarm_rule_channel`、`alarm_rule_receiver`、`notify_channel`、`sys_config`、`sys_dict`、`sys_dict_item`、`sys_menu`、`sys_role_menu`、`sys_role_data_scope` —— **共 17 张新表**，加上 18 张现有表，改版后总计 35 张表。

---

## 四、功能地图（6 大模块 · 20 个页面）

> 字段直接取自原型 MOCK 数据；枚举值取自 `MOCK.dicts` 或组件内的映射数组。
> 正文写法约定：`1=已启用` 表示「取值 1 对应中文标签『已启用』」。

### 4.1 概览（1 页）

#### `dashboard` 概览

| 项 | 内容 |
|---|---|
| **页面目标** | 一屏看清全局健康度、待办事项与风险，并可直接跳转到处理页 |
| **内容区** | ① 5 张指标卡 ② 待办事项 ③ 风险提示 ④ 调用量趋势 ⑤ 成功率趋势 ⑥ Top 接口 ⑦ Top 应用 |
| **指标卡字段** | `label`/`value`/`sub`/`trend`/`icon`/`kpiColor`/`suffix`<br>5 个：接口总数(12，已发布9/总数12)、应用总数(9，内部6·外部2·测试1)、近24h调用量(4053789，较昨日+18.2%)、近24h成功率(99.86%，失败5672次，suffix%)、近24h平均延迟(68ms，P99 420ms)<br>trend ≥0 显示 ▲ 绿色，<0 显示 ▼ 红色 |
| **待办字段** | `type`/`label`/`count`/`desc`/`route`（点击跳转）<br>5 类：api 待审核接口(1)、grant 待审批授权(1)、app 待审核应用(1)、cred 密钥即将过期(2)、alarm 未处理告警(4，其中严重2条) |
| **风险字段** | `level`(high/medium/low)/`text`/`action`/`route`。按严重程度排序<br>示例：high "星海数据科技近1小时3次重放攻击拦截"、high "数据看板密钥 ak_prod_1f5a****9046 已于 2026-08-01 过期"、medium "授权【订单中心-结算系统 × 支付查询】2026-09-30 到期，剩余32天"、medium "云图供应链配额使用率99.1%"、low "库存查询连续30天无调用" |
| **Top 接口** | name/path/calls/successRate/avgCost（成功率 <99.9% 标黄） |
| **Top 应用** | name/calls/successRate |
| **主要操作** | 趋势范围切换（近24小时/近7天/近30天）· 待办点击跳转·风险"处理"按钮跳转 |
| **关键业务规则** | ① 指标卡数值**必须走预聚合统计表**，禁止直查 `api_call_log`（原型原话）② 数据受数据权限过滤（业务线管理员只看本业务线）③ 待办/风险的重定向目标受角色权限约束（无权限则隐藏该条） |
| **与现有实现差异** | 🆕 现有只有数据大屏（overview/trend/app-rank/interface-rank/recent-events），**无待办、无风险提示、无 Top 榜单的合并视图**。5 个指标数据源复用现有大屏接口 🆕 新增两个聚合接口 |

---

### 4.2 应用管理（1 页）

#### `app-list` 应用列表

| 项 | 内容 |
|---|---|
| **页面目标** | 调用方身份、密钥、授权与配额的统一管理入口 |
| **列表字段** | 应用名称(`appName` + `appCode` 副标题)/业务线(`lineName`)/类型(`appType`)/状态(`status`)/密钥数(`credCount`，>1 标黄)/授权接口(`grantCount`)/近24h调用(`call24h`)/负责人(`ownerName`)/操作(详情·密钥·授权) |
| **筛选条件** | 关键词（应用名称/编码）· 业务线（下拉全量）· 类型（1内部/2外部合作方/3测试应用）· 状态（0待审核/1已启用/2已禁用/3已注销） |
| **主要操作** | 新建应用·批量导入（CSV）· 行内：详情/密钥/授权 |
| **详情抽屉**（72% 宽，6 Tab） | **基本信息**：应用编码/所属业务线/应用类型/状态/负责人/联系人(contactName·contactInfo)/可用环境(envScope 多 tag)/授权需审批/应用描述<br>**Tab1 密钥管理**：`alias`/`appKey`/`secretMask`(如 `Yk3m****J5sU`)/`envCode`/`status`(+轮换中 tag)/`expireTime`(空显示"长期有效")/`lastUsedTime`/操作(重置·吊销)<br>**Tab2 已授权接口**：接口(apiName + method/path)/分组/环境/QPS/日配额使用(进度条 `usedToday`/`dailyQuota`)/有效期/状态/操作(调整·撤销)<br>**Tab3 配额与限流**：环境(只读)/全局QPS/日调用上限/月调用上限/并发上限/保存<br>**Tab4 IP 白名单**：IP/CIDR/环境/备注/创建时间/删除<br>**Tab5 审计记录**：时间/操作人/模块/操作/对象/高危（取该应用最近 5 条） |
| **关键业务规则** | ① **Secret 仅创建时展示一次**，关闭后不可再看；新建密钥弹窗须勾选"我已将 Secret 妥善保存至安全的配置中心"才能确认（未勾选 → warning）② 多套密钥并存平滑轮换（`rotateFlag=1` 标"轮换中"）③ **吊销密钥必须填原因（≥5 字符）并写安全审计日志** ④ **撤销授权二次确认**："撤销后该应用调用【X】将立即返回 403" ⑤ 密钥状态：1启用中/2已停用/3已吊销/4已过期 ⑥ 配额**双层**：应用级 + 授权级(App×API)，超出返回 429 ⑦ **外部合作方(`appType=2`)强制配置 IP 白名单**，为空红警"该应用可从任意来源 IP 调用" ⑧ 日配额使用率 >95% 红、>80% 黄、否则绿 |
| **与现有实现差异** | 🔧 现有 `AppController` 只有 list/create/update/status/delete + ip-whitelist CRUD + rate-limit + reset-secret。字段与拆表改造见 §三 C1–C4；本页新增应用详情抽屉与应用级审计 Tab（均现有无） |

---

### 4.3 接口管理（2 页）

#### `api-group` 接口分组

| 项 | 内容 |
|---|---|
| **页面目标** | 接口目录的导航结构，也是授权与数据权限的批量粒度 |
| **列表字段** | 分组名称(`groupName` + `groupCode` 副标题)/所属业务线(`lineName`)/负责人(`ownerName`)/接口数(`apiCount` tag)/说明(`remark`)/排序(`sort`)/操作(编辑·查看接口) |
| **筛选条件** | 无（原型未设筛选，全量展示 5 条） |
| **主要操作** | 新建分组·编辑·查看接口（跳转 `api-list` 并带 `groupId` 过滤） |
| **新建表单** | 分组编码（如 `order-svc`，**创建后不可修改**）/ 分组名称/所属业务线（下拉）/ 负责人（姓名或工号）/ 说明 |
| **关键业务规则** | ① `groupCode` 创建后不可修改 ② 删除分组前需校验 `apiCount = 0`（推断，原型未实现删除） ③ 分组是数据权限的三维之一 |
| **与现有实现差异** | 🔧 现有 `api_group` 为树形（有 `parent_id`），原型为扁平；字段改造见 §三 B1（保留 `parent_id` 但 P0 不启用） |

#### `api-list` 接口列表（含详情 7 Tab）

| 项 | 内容 |
|---|---|
| **页面目标** | 接口资产登记：元数据、参数定义、版本、环境配置与在线文档 |
| **列表字段** | 接口名称(`apiName` + `apiCode` 副标题)/路径(method tag + `path`)/分组(`groupName`)/版本(`version` tag)/可见性(1内部/2对外)/状态/授权应用(`grantCount`)/近24h调用(`call24h`)/成功率(`successRate`，<99.5% 标黄)/负责人/操作(详情·授权) |
| **筛选条件** | 关键词（名称/路径/编码）· 分组·状态（0草稿/1待审核/2已发布/3已弃用/4已下线）· 可见性（1内部/2对外公开） |
| **主要操作** | 新建接口·Swagger 导入·行内：详情/授权 |
| **详情抽屉 76%（7 Tab）** | **Tab1 基本信息**：接口编码/所属分组/业务线/当前版本/传输安全要求/需要授权/负责人/SLA承诺/标签(tags)/近24h调用(调用量·成功率·平均耗时)/敏感字段(`sensitiveCount` 个 + 平台脱敏字段 mobile/idCard/bankCard)/接口说明<br>**Tab2 参数定义**：字段名(+必填tag)/位置/类型/示例/错误码/HTTP/敏感·加密/说明。树形（`parentId` 自关联，默认全展开）<br>**Tab3 版本管理**：版本(+当前tag)/状态/灰度比例(进度条)/弃用时间/计划下线/变更说明<br>**Tab4 环境配置**：环境/后端地址/连接超时/读取超时/重试/Mock/状态/操作(连通测试·编辑)<br>**Tab5 在线文档**：请求参数(Body)/响应字段/错误码/curl 请求示例<br>**Tab6 授权情况**：应用/环境/QPS/日配额/有效期至/状态/授权理由/操作(调整·撤销)<br>**Tab7 变更记录**：时间/操作人/类型/字段/变更前/变更后/变更原因 |
| **枚举（严格区分两套）** | **接口状态** `api_status`：0草稿/1待审核/2已发布/3已弃用/4已下线<br>**版本状态**：0开发中/1已发布/2已弃用/3已下线（⚠ 与接口状态**不同**，不要复用同一字典）<br>其余枚举（参数位置 `paramType`、字段类型 `fieldType`、加密规则 `encryptRule`、敏感标记 `sensitive`、必填 `required`、传输安全 `transportSecurity`、环境配置状态 `configStatus`） |
| **关键业务规则** | ① 「从 JSON 示例生成结构」：递归解析字段树（**object 递归展开，array 不递归**）② 写操作接口（POST/PUT/DELETE）重试次数默认 0，防重复提交 ③ 连通测试成功后 `configStatus` 置"已验证"，失败提示检查后端地址与网络策略 ④ 只有"已发布"接口出现在授权矩阵与授权选择器 ⑤ 字段变更写 `api_change_log`，变更原因必填 ⑥ 发布/下线为**高危操作**（`api:publish`/`api:offline`） |
| **与现有实现差异** | 🔧 现有 `api_interface` 仅 17 字段；字段与拆表改造见 §三 B2、B5–B7。本页新增参数定义、版本管理、环境配置、在线文档、变更记录 5 个 Tab（均新建），Swagger 导入为 P2 |

---

### 4.4 权限管理（5 页）

#### `perm-user` 用户管理

| 项 | 内容 |
|---|---|
| **页面目标** | 后台登录用户、角色分配与账号状态 |
| **列表字段** | 姓名/账号/工号(`empNo`)/部门(`dept`)/业务线(`lineName`)/角色(多 tag)/状态/最后登录(`lastLoginTime`)/最后登录IP(`lastLoginIp`)/操作(编辑·重置密码·停用/启用) |
| **筛选条件** | 关键词（姓名/账号/工号）· 状态（1正常/0停用） |
| **编辑表单** | 账号/姓名/邮箱/手机号/角色（多选）/ 状态（开关） |
| **关键业务规则** | ① 手机号**列表即脱敏**展示（`138****0001`）② 用户管理为高危权限点 `sys:user:update` ③ 停用后该用户所有 Token 立即失效（推断） |
| **与现有实现差异** | 🔧 现有 `SystemController` 已有 user CRUD + 重置密码 + 启停；差异：加 `emp_no`/`dept`/`line_id`、角色多选、列表新增工号/部门/业务线/最后登录IP 列 |

#### `perm-role` 角色管理

| 项 | 内容 |
|---|---|
| **页面目标** | 控制面权限：角色决定用户能操作后台的哪些功能 |
| **列表字段** | 角色名称(+内置 tag，roleType=1 时显示)/角色编码/数据范围(`dataScope` tag)/用户数(`userCount`)/说明/状态/操作(配置权限·数据权限·编辑) |
| **主要操作** | 新建角色·配置权限（权限树弹窗）· 数据权限（范围弹窗）· 编辑 |
| **权限树弹窗** | 6 大模块 × 29 个权限点，`el-tree` + checkbox；节点显示 名称 + `permCode` + 高危红标；**保存后清除该角色下所有在线用户的权限缓存**；若勾选了高危权限点，保存后弹持久通知："已授予高危权限，包含 N 个高危操作点：xxx、yyy" |
| **数据权限弹窗** | 业务线（多选 checkbox）/ 环境（多选 checkbox）/ 接口分组（多选，不限则留空） |
| **关键业务规则** | ① **权限点粒度到「操作」而非只到「菜单」** ② 高危操作点单独授权、操作强制二次确认并实时通知管理员 ③ **数据权限在服务端强制（MyBatis 拦截器自动拼 SQL 条件），前端隐藏菜单只是体验优化，不是安全边界** ④ 内置角色（roleType=1）不可删除 |
| **页面顶部说明**（原型原话） | "注意区分两套权限：本页管理的是「谁能操作后台」（控制面 RBAC）；「哪个应用能调哪个接口」属于数据面 ACL，在应用/接口的授权功能中配置。" |
| **与现有实现差异** | 🔧 现有 `sys_role` 无任何权限点关联；差异见 §三 E2/E3：新建 `sys_menu` + `sys_role_menu` + `sys_role_data_scope`，拦截器升级为校验 `permCode`，加 `role_type`/`data_scope` |

#### `perm-matrix` 接口授权总览

| 项 | 内容 |
|---|---|
| **页面目标** | App × API 授权矩阵：批量治理与越权排查，10 秒内回答"有哪些外部应用能调核心订单接口" |
| **筛选条件** | 环境（下拉，默认 prod）· 仅看外部应用（开关）· 视图（矩阵/列表） |
| **矩阵视图** | 行 = 已启用应用（`status=1`），列 = 已发布接口（`status=2`）。单元格 = 授权状态文字（待审批/✓/已过期/已撤销/已驳回）+ 日配额使用率百分比。样式：已授权绿/非生效状态黄/未授权灰。图例说明"单元格内百分比为日配额使用率" |
| **列表视图字段** | 应用/接口(+method/path)/分组/环境/QPS/有效期至/状态/申请人(`applicantName`)/授权理由(`grantReason`)/操作(待审批时：通过·驳回；否则：调整·撤销) |
| **主要操作** | 批量授权（多选应用 × 多选接口）· 导出矩阵（Excel）· 单元格点击（已授权→详情/未授权→新增授权）· 通过/驳回/调整/撤销 |
| **关键业务规则** | ① 矩阵行只含**已启用**应用、列只含**已发布**接口（草稿/已下线不进矩阵）② 授权唯一键为 `(appId, apiId, envCode)` ③ 授权状态五态：0待审批/1已生效/2已过期/3已撤销/4已驳回 ④ 过期由定时任务自动置位（推断）⑤ 审批操作权限点 `grant:approve`，撤销为高危 `grant:revoke` |
| **与现有实现差异** | 🔧 现有 `PermissionController` 有 list/create/batch/grant-by-group/delete；差异见 §三 D1–D3：授权记录加环境/配额/有效期/人/理由，新增五态状态机与按环境切换的矩阵视图与导出，审批流 P0 关闭 P2 开启 |

#### `perm-datascope` 数据权限

| 项 | 内容 |
|---|---|
| **页面目标** | 按业务线/环境/接口分组隔离数据可见范围 |
| **布局** | 左侧（8 列）角色列表（点击切换，显示角色名 + dataScope tag）；右侧（16 列）可见数据范围配置 + 效果预览 |
| **配置表单** | 业务线（多选 checkbox）· 环境（多选 checkbox）· 接口分组（多选，**不限则留空**）· 保存 |
| **效果预览** | 实时展示："该角色登录后可见：**应用**：xxx、yyy；**接口**：zzz、www"（按选中业务线过滤 `MOCK.apps`/`MOCK.apis`） |
| **关键业务规则** | ① 同 `perm-role` 的数据权限弹窗，此为独立页面版（两处配置同一份数据）② 配置为空 = 该维度不限 ③ 服务端强制 |
| **与现有实现差异** | 🆕 完全新建（现有无任何数据权限能力） |

#### `perm-audit` 操作审计

| 项 | 内容 |
|---|---|
| **页面目标** | 后台所有写操作的留痕，高危操作强制记录且不可关闭 |
| **列表字段** | 时间/操作人/操作IP/模块/操作/权限点(`permCode`)/高危(riskFlag)/对象(`objectDesc`)/变更内容(`changeContent` JSON)/结果(1成功/0失败) |
| **筛选条件** | 是否高危（1仅高危/0仅普通）· 模块（APP/API/GRANT/SYSTEM/ROLE/AUTH） |
| **主要操作** | 导出（走现有异步导出中心） |
| **关键业务规则** | ① **审计日志只追加、禁删改。应用使用的数据库账号对审计表只有 INSERT 权限，无 UPDATE/DELETE 权限，满足等保三级要求** ② 失败操作也要记录（`result=0` + `failReason`，如登录失败 `{"failCount":3}`）③ 变更内容存 JSON 字符串（`changeContent`）④ 审计导出本身是高危操作 `audit:export` |
| **与现有实现差异** | 🔧 现有 `sys_operation_log` 有 16 字段；差异见 §六 6.4：加 `risk_flag`/`perm_code`/`object_desc`/`change_content`(JSON diff)/`result`/`fail_reason`，模块枚举统一为 APP/API/GRANT/SYSTEM/ROLE/AUTH |

---

### 4.5 系统设置（8 页）

#### `sys-env` 环境与网关

| 项 | 内容 |
|---|---|
| **页面目标** | 运行环境定义、网关入口地址与全局默认超时 |
| **区域1 环境列表** | 环境编码(`envCode` tag)/环境名称(`envName`)/网关入口地址(`gatewayUrl`)/排序(`sort`)/状态(1=启用 success/0=停用 info)/编辑<br>4 条：dev `https://api-dev.example.com`、test `https://api-test.example.com`、pre `https://api-pre.example.com`、prod `https://api.example.com` |
| **区域2 网关全局配置** | 默认连接超时(1000ms)/默认读取超时(3000ms)/HTTPS 证书到期(只读 + 正常 tag)/保存 |
| **区域3 鉴权链路**（`el-steps` 纵向 10 步） | 时间戳容差校验（±5分钟，内存计算，零成本）→ Nonce 去重（Redis SETNX，防重放）→ AppKey 查证（本地缓存→DB）→ 应用状态校验（是否已禁用/注销）→ 签名比对（HMAC-SHA256，恒定时间比较）→ IP 白名单校验 → 接口 ACL 校验（App × API 授权关系）→ 配额与限流（Redis + Lua 令牌桶）→ 转发后端（注入 appId/apiId/traceId）→ 异步记录调用日志（不阻塞主链路） |
| **关键业务规则** | ① **顺序原则：先验廉价条件，昂贵签名计算在中段，配额消耗必须在身份确认之后（否则伪造签名可耗尽对方配额）** ② **性能预算 P99 < 15ms** ③ 环境列表变更影响所有 env 维度的配置与缓存 |
| **与现有实现差异** | 🆕 环境表与页面全新。⚠ 链路顺序差异：现有 `白名单→封禁→认证→限流→权限→解密→转发`，原型 `认证(细分5步)→白名单→ACL→限流→转发`；核心原则（配额在身份之后）一致，顺序差异建议 P2 对齐 |

#### `sys-security` 安全策略

| 项 | 内容 |
|---|---|
| **页面目标** | 签名算法、重放防护、密钥生命周期与账号安全 |
| **列表字段** | 配置项(`configName`)/配置键(`configKey`)/当前值（true→绿"已开启"、false→红"已关闭"、其他→code + 敏感 tag）/ 说明(`remark`)/修改 |
| **11 条 SECURITY 配置** | `sign.algorithm`=HmacSHA256（支持 HmacSHA256/HmacSHA512）· `sign.timestamp.tolerance`=300000（±5分钟，超出直接拒绝）· `sign.nonce.ttl`=600（应 ≥2倍时间戳容差）· `secret.length`=32·`secret.encrypt.algo`=AES-256-GCM（敏感，可逆加密，签名校验需原始值）· `key.rotate.period`=180（超期在概览页告警）· `key.max.valid.days`=365（到期自动失效）· `external.ip.whitelist.required`=true·`login.fail.threshold`=5·`session.timeout`=480·`log.desensitize`=true（手机号/身份证/银行卡） |
| **修改弹窗（高危）** | 顶部红色警示条"正在修改：{configName}（{configKey}）"；字段：原值/新值/**变更原因（≥10 字符）**/勾选"我已知晓该变更会降低/调整系统安全水位，并承担相应责任"。保存后：写审计日志 + **实时通知所有平台管理员** |
| **签名规范速查** | StringToSign 7 行 + `Signature = Base64(HMAC-SHA256(StringToSign, Secret))`；请求头 `X-Api-AppKey`/`X-Api-Timestamp`/`X-Api-Nonce`/`X-Api-Signature`；Secret 采用 AES-256-GCM 可逆加密存储、主密钥由 KMS 或环境变量注入绝不入库（完整串见 §7.3） |
| **关键业务规则** | ① 修改安全策略需填原因 + 二次确认 + 通知所有平台管理员（"防止内部人悄悄降低安全水位的关键机制"）② `gateway.auth.enabled` 关闭等于裸奔，仅应急临时关闭 |
| **与现有实现差异** | 🆕 页面全新。配置项部分已在 `application.yml`（`login-fail-threshold:5`、`security.*`），需迁入 `sys_config` 并支持热更新；`GATEKEEPER_AES_KEY`/`JWT_SECRET` 保持环境变量注入不入库 |

#### `sys-bizline` 业务线管理

| 项 | 内容 |
|---|---|
| **页面目标** | 数据权限隔离与统计维度的基础数据 |
| **列表字段** | 业务线编码(`lineCode`)/业务线名称(`lineName`)/负责人(`ownerName`)/成员数(`memberCount`)/说明(`remark`)/状态/操作(编辑·成员) |
| **新建表单** | 业务线编码（如 `trade`，**创建后不可修改**）/ 业务线名称/负责人/说明 |
| **关键业务规则** | ① `lineCode` 创建后不可修改 ② 业务线被应用/接口/分组引用后不可删除（推断）③ 是数据权限的第一维度 |
| **与现有实现差异** | 🆕 完全新建 |

#### `sys-dict` 字典管理

| 项 | 内容 |
|---|---|
| **页面目标** | 系统枚举值的统一维护 |
| **布局** | 字典下拉选择（显示 `dictName（dictCode）`）→ 下方该字典的字典项表格 + 新增按钮 |
| **字典项字段** | 字典项值(`itemValue`)/显示名称(`itemLabel`)/排序(`sort`)/状态/操作(编辑·停用) |
| **6 个内置字典** | `app_type`(1内部系统/2外部合作方/3测试应用)·`visibility`(1内部/2对外公开)·`api_status`(0草稿/1待审核/2已发布/3已弃用/4已下线)·`grant_status`(0待审批/1已生效/2已过期/3已撤销/4已驳回)·`cred_status`(1启用中/2已停用/3已吊销/4已过期)·`alarm_level`(1提示/2警告/3严重) |
| **关键业务规则** | ① **字典项的值（item_value）被程序引用后不可修改，只允许改显示名称和停用状态** ② builtIn=1 的字典不可删除 |
| **与现有实现差异** | 🆕 完全新建（现有枚举散落 Java 常量与前端硬编码） |

#### `sys-alarm` 告警规则

| 项 | 内容 |
|---|---|
| **页面目标** | 阈值、静默期、通知渠道与接收人配置 |
| **列表字段** | 规则名称/告警类型(`alarmType`)/阈值(`threshold`)/统计窗口(`timeWindow` 分钟)/级别(`alarmLevel` 1提示/2警告/3严重)/静默期(`silencePeriod` 分钟)/通知渠道(`channelNames` 多 tag)/接收人(`receiverNames`)/状态 |
| **告警类型枚举（8 种）** | `FAIL_RATE`调用失败率·`AVG_LATENCY`平均延迟·`QPS_SURGE`QPS突增·`QUOTA_USAGE`配额使用率·`AUTH_FAIL`鉴权失败·`CERT_EXPIRE`证书到期·`KEY_EXPIRE`密钥到期·`ZOMBIE_API`僵尸接口 |
| **7 条初始化规则** | 调用失败率(>5%, 5min, 严重, 静默30min)·鉴权失败(>10, 5min, 严重, 静默10min)·配额使用率(>80, 60min, 警告, 静默120min)·后端超时(>10000, 5min, 警告, 静默30min)·密钥即将过期(提前30天, 1440min, 警告, 静默1440min)·僵尸接口(30天无调用, 43200min, 提示, 静默10080min)·QPS突增(>200%基线, 5min, 警告, 静默30min，**已停用**) |
| **关键业务规则** | ① **静默期防止同一规则反复触发造成告警轰炸** ② "鉴权失败类告警无论次数立即告警——这几乎一定意味着攻击或配置错误" ③ 接收人支持角色化（"各应用负责人"/"各接口负责人"，推断实现为动态接收人解析） |
| **与现有实现差异** | 🆕 完全新建。**不要复用 `security_rule`**（语义不同：`security_rule`＝检测到就封禁；`alarm_rule`＝统计超阈值就通知）。⚠ 需补 4 类承接现有安全检测：`PERM_BREACH`/`ABNORMAL_PARAM`/`HIGH_FREQ`/`OFF_HOURS`（推断） |

#### `sys-notify` 通知渠道

| 项 | 内容 |
|---|---|
| **页面目标** | 告警消息的投递方式与连通性验证 |
| **列表字段** | 渠道名称/类型(`channelType`)/状态/最后测试(`lastTestTime`)/测试结果(`lastTestResult`)/操作(发送测试·配置) |
| **类型枚举** | `WECOM`企业微信·`DINGTALK`钉钉·`EMAIL`邮件·`WEBHOOK`Webhook |
| **主要操作** | 新建渠道·发送测试（异步，回填 lastTestTime/lastTestResult，成功绿"发送成功"，失败黄"连接超时，请检查配置"）· 配置 |
| **关键业务规则** | ① 渠道停用时不参与告警投递 ② 测试结果持久化，供运维排查 ③ webhook 地址/密钥属敏感配置，列表不展示明文（推断） |
| **与现有实现差异** | 🆕 完全新建（现有告警仅站内）。对应路线图 RICE 3.6 项，本次提前到 P1 |

#### `sys-config` 参数配置

| 项 | 内容 |
|---|---|
| **页面目标** | 平台级开关与阈值 |
| **布局** | 配置分组下拉 → 表格 |
| **分组** | `SECURITY`安全策略（11 条）· `GATEWAY`网关（3 条）· `LOG`日志（2 条）· `DEFAULT`默认（2 条） |
| **列表字段** | 配置名称/配置键/配置值（true→绿 tag、false→灰 tag、其他→code + 敏感 tag）/ 说明/修改 |
| **18 条内置配置** | SECURITY(11，同 sys-security)·GATEWAY：`gateway.auth.enabled`=true(敏感)/`gateway.ratelimit.enabled`=true/`gateway.default.read.timeout`=3000·LOG：`audit.log.retention.days`=180(等保三级要求≥180)/`call.log.hot.days`=30·DEFAULT：`approval.enabled`=**false**（MVP 关闭，V2 开启）/ `export.max.rows`=50000 |
| **关键业务规则** | ① `builtIn=1` 的配置不可删除，只能改值 ② `sensitive=1` 的配置展示需二次点击才可见（推断）③ 修改后热生效（需清缓存/广播） |
| **与现有实现差异** | 🆕 完全新建（现有全在 `application.yml`）。⚠ **启动密钥（`GATEKEEPER_AES_KEY`/`JWT_SECRET`）不入库**，保持环境变量 + `SecurityStartupCheck` 校验 |

#### `sys-log` 日志与审计设置

| 项 | 内容 |
|---|---|
| **页面目标** | 保留期、脱敏规则与归档策略 |
| **区域1 保留期** | 调用日志热数据（1-90 天，默认 30，MySQL 保留时长，超时归档）· 调用日志冷归档（30-730 天，默认 180，对象存储/ClickHouse）· 操作审计日志（≥180 天，默认 180，等保三级要求）· 保存 |
| **区域2 脱敏规则** | 开启脱敏（开关，默认开）· 脱敏字段（多选：手机号/身份证号/银行卡号/邮箱/姓名） |
| **区域3 容量与归档策略** | 4 个定时任务（清单见 §7.2）：调用日志建表（每日 01:00，提前创建明天的 `api_call_log_yyyyMMdd`）· 调用日志归档（每日 04:00，迁移超期数据到冷存储并删除原表）· 审计日志归档（每月 1 日）· 统计数据聚合（每 5 分钟，按应用/接口聚合供 Dashboard 读取） |
| **关键业务规则** | ① **脱敏在日志写入时生效，已写入的历史日志不追溯处理** ② **Secret、签名等凭证字段永远不写入日志** ③ 调用日志须按天分表 + 异步写入；检索 P95 > 3s 时应切 ClickHouse/ES 而非继续优化 MySQL（日均 5000 万时单表 30 天 15 亿行，MySQL 单表不可行）④ Dashboard 不要直查 `api_call_log` 原始表，必须走预聚合统计表 |
| **与现有实现差异** | 🆕 页面全新。⚠ 口径冲突：现有 `gatekeeper.log.retention-days: 90`（单值单表）vs 原型热 30 + 冷 180，建议统一为热 30 → 冷 180 → 删除（总计 180 天）；按天分表与冷归档属 P2 |

---

### 4.6 监控与审计（3 页）

#### `mon-calllog` 调用日志

| 项 | 内容 |
|---|---|
| **页面目标** | 全量调用记录检索与链路详情（含被拦截原因定位），失败原因精确到"被哪一道防线拦下" |
| **列表字段** | 时间/应用/接口(+method/path)/环境/状态(`httpStatus`，<300绿/<500黄/≥500红)/拦截阶段(`rejectStage`，无则显示绿"正常")/错误码(`errorCode`)/客户端IP/总耗时(`totalCost`)/详情 |
| **筛选条件** | 时间范围（datetimerange）· 结果（成功/失败）· 拦截阶段（8 种）· 关键词（应用/接口/traceId） |
| **拦截阶段枚举（8 种）** | `TIMESTAMP`时间戳校验·`NONCE`Nonce去重·`APPKEY`AppKey查证·`SIGNATURE`签名比对·`IP`IP白名单·`ACL`接口授权·`RATELIMIT`配额限流·`UPSTREAM`后端转发 |
| **错误码示例（取自 MOCK）** | `API_NOT_AUTHORIZED`(403/ACL)·`RATE_LIMIT_EXCEEDED`(429/RATELIMIT)·`SIGNATURE_MISMATCH`(401/SIGNATURE)·`INVALID_APP_KEY`(401/APPKEY)·`REPLAY_ATTACK`(401/NONCE)·`UPSTREAM_TIMEOUT`(504/UPSTREAM) |
| **详情抽屉（60%）** | 基础信息（TraceId/调用时间/应用/接口/环境/HTTP状态/客户端IP/拦截阶段/错误码）+ **各阶段耗时**（鉴权耗时 `authCost`/后端耗时 `upstreamCost`/总耗时 `totalCost`）+ 请求头（**已脱敏**：`X-Api-AppKey: ak_prod_7f3a****d605`、`X-Api-Signature: K8fJ****（已脱敏）**）+ 请求体 + 响应体 |
| **主要操作** | 详情·导出（走现有异步导出中心） |
| **关键业务规则** | ① 日志保留期受 `sys-log` 配置约束 ② 敏感字段脱敏后展示 ③ 明细查询受数据权限过滤（应用/接口/环境三维度） |
| **与现有实现差异** | 🔧 现有 `api_call_log` 有 `is_rate_limited`/`is_blocked`/`block_reason` 但**无 `reject_stage`**；差异见 §三 G1：加 `trace_id`/`env_code`/`error_code`/`reject_stage`/`auth_cost`/`upstream_cost`，`AppAuthHandler` 拆 5 子阶段 |

#### `mon-alarm` 告警记录

| 项 | 内容 |
|---|---|
| **页面目标** | 告警历史、认领与处理；高危告警可一键封禁作恶来源 |
| **列表字段** | 触发时间/级别(1提示/2警告/3严重)/规则(`ruleName`)/告警内容(`alarmContent`)/告警对象(`scopeDesc`)/触发值(`triggerValue`)/状态(`handleStatus`)/处理人/操作(处理·封禁·查看详情) |
| **处理状态枚举（4 态）** | 0未处理（红）· 1处理中（黄）· 2已处理（绿）· 3已忽略（灰） |
| **筛选条件** | 处理状态（未处理/处理中/已处理/已忽略） |
| **处理操作** | 弹窗填"处理备注"→ 提交后置为已处理，记录 `handlerName` + `handleTime` + `handleRemark` |
| **一键封禁弹窗** | 封禁层级（IP/AppKey/网段）· 封禁对象（IP/CIDR/AppKey，从 `scopeDesc` 预填）· 触发原因（`MANUAL`/`REPLAY_ATTACK`/`SIGNATURE_MISMATCH`/`IP_NOT_ALLOWED`）· 封禁时长（10分钟/1小时/24小时/**永久（需审批解封）**）· 原因说明（预填告警内容） |
| **关键业务规则** | ① 封禁后跳转提示"已加入封禁名单，可在「封禁管理」查看" ② 告警级别由 `alarm_rule.alarmLevel` 决定 ③ 站内红点 + 多渠道投递（P1） |
| **与现有实现差异** | 🔧 现有分裂为 `alert` + `security_event`；差异见 §三 G2：统一为 `alarm_record`，加 `rule_id`/`scope_desc`/`trigger_value`/`handler_name`/`handle_time`，`handle_status` 扩 4 态，新增一键封禁联动 |

#### `mon-block` 封禁管理

| 项 | 内容 |
|---|---|
| **页面目标** | 调用异常触发的动态黑名单：封禁 IP/AppKey/网段，拦截重放、爆破与探测 |
| **区域1 封禁规则（5 条）** | 层级(`scope` tag：IP=warning/APP=danger/PLATFORM=danger，另有 ACCOUNT=info 仅存在于映射未启用)/说明(`desc`)/触发原因码(`reasonCode`)/阈值(`threshold`)/默认时长(`ttl`)/状态(`enabled`：true=已启用 success/false=已关闭 info)/`auto`（true=自动触发受全局开关控制、false=人工触发，**原型未渲染**）<br>`IP`+`REPLAY_ATTACK`重放攻击（同IP 5min≥20次/10分钟）<br>`APP`+`SIGNATURE_MISMATCH`签名连续失败（同AppKey 10min≥50次/1小时）<br>`APP`+`RATE_LIMIT_EXCEEDED`配额击穿（持续命中限流>5min/30分钟）<br>`IP`+`IP_NOT_ALLOWED`白名单外反复探测（同IP 5min≥30次/1小时）<br>`APP`+`MANUAL`人工封禁（人工触发/自定义，**enabled=true**） |
| **区域2 封禁名单** | 层级(`scope` tag)/封禁对象(`target` + `envCode` 副标题)/原因码(`reasonCode`)/原因(`reasonDetail`)/类型·次数(`blockType` 自动|人工·`blockTimes`)/时长(`ttlSeconds` → 0=永久/<3600=X分钟/<86400=X小时/≥86400=X天)/到期(`expireTime`)/状态(1生效中红/0已解封灰)/操作(解封) |
| **筛选条件** | 状态（生效中/已解封） |
| **主要操作** | 新建封禁（弹窗，字段同 `mon-alarm` 的一键封禁）· 解封 |
| **顶部醒目提示** | "**生产默认只告警不自动封禁**。全局自动封禁开关 `blocklist.auto.enabled = false`；IP 级自动封禁默认关闭，避免 NAT/共享出口误伤。可在「参数配置」灰度开启并观察告警曲线。" |
| **关键业务规则** | ① **5 条规则中 4 条 auto 规则默认 `enabled=false`**，仅 MANUAL 人工封禁启用 ② **解封必须填原因**（`unblockReason`）；**永久封禁（ttlSeconds=0）解封需审批**（原型提示"永久封禁解封需审批，请填写原因"）③ 解封后记录 `unblockBy`/`unblockReason`/`unblockTime`，`status=0` **但记录不删除**（用于 blockTimes 统计与规则调优）④ 连续被封次数 `blockTimes` 累加，可用于升级封禁时长（推断）⑤ 封禁维度新增"网段"（`PLATFORM`）与 AppKey（`APP`），现仅 IP |
| **与现有实现差异** | 🔧 现有 `ip_ban` 仅 IP 维度、无 reasonCode/ttl/blockTimes/解封留痕；差异见 §三 G3：`ip_address` 泛化为 `target` + `scope` 枚举，新增 `block_rule` 5 条（与 `security_rule` 并存） |

---

## 五、核心业务流程

### 5.1 流程一：应用接入 → 凭证发放 → 接口授权审批 → 联调 → 上线

```
[应用负责人]                    [平台管理员/业务线管理员]         [接口提供方]
     │                                    │                          │
 ① 新建应用                                                          
     │  appCode/名称/业务线/类型/可用环境(envScope)/联系人/描述       
     │  → app.status = 待审核(0)                                     
     ▼                                                              
 ② 审核启用 ────────────────────►  审核通过 → status=已启用(1)        
     │        外部合作方(appType=2)强制校验 IP 白名单非空              
     │        (external.ip.whitelist.required=true)                  
     ▼                                                              
 ③ 创建凭证（按环境）                                                 
     │  生成 appKey = ak_{env}_16位hex                               
     │  生成 Secret = 32 位随机串（secret.length=32）                
     │  ┌──────────────────────────────────────┐                    
     │  │ Secret 仅此一次完整展示！              │                    
     │  │ 必须勾选「已妥善保存」才能关闭弹窗     │                    
     │  └──────────────────────────────────────┘                    
     │  落库：secretMask（Yk3m****J5sU）+ AES-256-GCM 密文           
     │  → credential.status = 启用中(1)                              
     ▼                                                              
 ④ 提交授权申请                                                       
     │  选择 接口 + 环境 + QPS + 日配额 + 有效期 + 授权理由           
     │  → grant.status = 待审批(0)，记录 applicantName                
     ▼                                                              
 ⑤ 审批 ────────────────────────►  通过 → status=已生效(1)  ─────────► (审批人可为接口提供方)
     │                              记录 auditorName + auditTime      
     │                              驳回 → status=已驳回(4)           
     │  注：approval.enabled=false 时跳过审批直接生效(1)，            
     │      仅记录 applicantName，auditorName 留空                    
     ▼                                                              
 ⑥ 联调（dev/test 环境）                                             
     │  用对应环境的 appKey/Secret 调网关                             
     │  失败 → 「调用日志」按 traceId 查 rejectStage 定位：           
     │        SIGNATURE → 签名串构造错误                              
     │        ACL       → 未授权该接口/环境                           
     │        RATELIMIT → 配额不足                                   
     │        IP        → 白名单未放行                                
     │  接口「环境配置」可开 Mock + 连通测试（configStatus→已验证）   
     ▼                                                              
 ⑦ 上线（prod 环境，重复 ③~⑤）                                       
     │  prod 凭证（expireTime ≤ key.max.valid.days=365 天）           
     │  prod 授权 + prod 配额（globalQps/dailyQuota/monthlyQuota/concurrency）
     │  观察概览页成功率/延迟/告警                                    
     ▼                                                              
 ⑧ 密钥轮换（key.rotate.period=180 天触发或人工）                     
       新建第二套密钥（alias="生产-轮换中(新)"，rotateFlag=1）         
       → 调用方灰度切换（新旧并存，两套都能用）                       
       → 观察 lastUsedTime，旧密钥无流量后                            
       → 吊销旧密钥（**必须填原因 ≥5 字符，写安全审计日志**）         
```

**关键约束**
- 步骤 ③ 的 Secret 一旦关闭弹窗**永不再次展示**（原型原话），重置是唯一补救手段且会中断业务——这是"多套并存轮换"存在的理由
- 步骤 ⑤ 审批人路由（推断）：优先接口负责人（`api.owner`）→ 兜底业务线管理员（`biz_line.owner`）→ 平台管理员
- 步骤 ⑧ 轮换窗口内两套凭证**同时有效**，网关按 `appKey` 独立校验，互不影响

---

### 5.2 流程二：接口发布与版本灰度

```
① 登记接口
   apiCode / 名称 / 路径 / 方法 / 分组 / 可见性(内部|对外) / 需要授权 /
   负责人 / SLA / 标签 / 传输安全要求 / 说明
   → api.status = 草稿(0)

② 参数定义
   「从 JSON 示例生成结构」自动解析（object 递归、array 不递归）
   或手动录入；四类位置：请求头(1) / Body字段(3) / 响应字段(4) / 错误码(5)
   标记 sensitive + encryptRule：NONE 不加密 | SYMMETRIC 对称加密 | MASK 掩码脱敏
   ※ 敏感字段是传输加密与日志脱敏的执行依据

③ 环境配置（dev / test / pre / prod 各自独立）
   upstreamUrl / connectTimeout / readTimeout / retryCount / mockEnabled
   ⚠ 写操作接口（POST/PUT/DELETE）retryCount 默认必须 = 0（防重复提交）
   → configStatus：未配置(0) → 已配置(1) →「连通测试」通过 → 已验证(2)

④ 提交发布审核 → api.status = 待审核(1)

⑤ 审核通过 → 创建版本 v1（status=已发布(1)，isCurrent=1，grayRatio=100）
   → api.status = 已发布(2)，api.current_version = v1
   → 写 api_change_log（changeType=PUBLISH，status：待审核 → 已发布）
   ※ 只有「已发布」接口才出现在授权矩阵与授权选择器

⑥ 不兼容变更 → 新建版本
   创建 v2：status=开发中(0)，isCurrent=0，grayRatio=0，changeLog=变更说明
   → 灰度放量：grayRatio 10% → 50% → 100%
   → 全量后：v2.isCurrent=1，status=已发布(1)
             v1.status=已弃用(2)，记录 deprecateTime
             设置 offlinePlanTime（如 2026-12-31）
   → 在线文档同步提供 v1/v2 两套参数与错误码，调用方按版本迁移

⑦ 版本下线
   到达 offlinePlanTime → v1.status = 已下线(3)
   接口全部版本下线后 → api.status = 已下线(4)

⑧ 僵尸接口治理
   连续 30 天无调用 → ZOMBIE_API 告警（级别：提示）
   → 负责人确认 → 走 ⑦ 下线

⑨ 全链路留痕
   所有元数据/参数/环境配置变更 → api_change_log
   字段：changeType(CREATE|UPDATE|PUBLISH) / fieldName / fieldLabel /
        oldValue / newValue / operatorName / createTime / changeReason（必填）
```

**关键约束**
- 版本状态枚举（0开发中/1已发布/2已弃用/3已下线）与接口状态枚举（0草稿/1待审核/2已发布/3已弃用/4已下线）**是两套，不可复用**
- 发布/下线为高危操作（`api:publish`/`api:offline`），需二次确认 + 审计
- 灰度比例的执行位置（推断）：网关按 `appId` 或 `traceId` 哈希取模，落在 `grayRatio` 内路由到新版本上游；P0 可先只做记录不做实际路由（灰度路由属 P2）

---

### 5.3 流程三：异常检测 → 告警 → 封禁 → 解封

```
① 检测（网关 + 定时任务）
   网关各阶段拦截 → 写 api_call_log，记录 rejectStage + errorCode
     TIMESTAMP | NONCE | APPKEY | SIGNATURE | IP | ACL | RATELIMIT | UPSTREAM
   现有 5 类安全检测继续产出 security_event：
     鉴权失败 / 权限越界 / 异常入参 / 高频调用 / 异常时段
   定时任务按 alarm_rule.timeWindow 聚合统计：
     FAIL_RATE | AVG_LATENCY | QPS_SURGE | QUOTA_USAGE |
     AUTH_FAIL | KEY_EXPIRE | CERT_EXPIRE | ZOMBIE_API

② 告警生成
   统计值超 threshold 且 不在 silencePeriod 内
   → 生成 alarm_record：
       ruleId / ruleName / alarmLevel / alarmContent /
       scopeDesc（如「应用：星海数据科技（外部）/ 生产环境」）/
       triggerValue（如「8.2%」）/ handleStatus = 未处理(0)
   ⚠ 静默期防轰炸；鉴权失败类建议不设静默或极短（10 分钟）

③ 投递
   按 alarm_rule 绑定的 channelNames → notify_channel 投递
     WECOM 企业微信 | DINGTALK 钉钉 | EMAIL 邮件 | WEBHOOK
   + 站内红点（现有能力）
   失败重试 + 记录 lastTestResult

④ 认领与处理
   值班人在「告警记录」：
     处理 → handleStatus = 处理中(1)
     填备注提交 → 已处理(2)，记录 handlerName + handleTime + handleRemark
     误报 → 已忽略(3)

⑤ 一键封禁（高危告警）
   选层级：IP / AppKey / 网段(PLATFORM)
   对象：从 scopeDesc 预填，支持 IP / CIDR / AppKey
   原因码：MANUAL | REPLAY_ATTACK | SIGNATURE_MISMATCH | IP_NOT_ALLOWED
   时长：600(10分钟) / 3600(1小时) / 86400(24小时) / 0(永久，需审批解封)
   原因说明：必填
   → 写入封禁名单，网关 IpBanCheckHandler 立即拦截后续请求

⑥ 自动封禁（默认关闭！）
   block_rule 5 条：
     IP  + REPLAY_ATTACK       同IP 5min≥20次    → 10分钟
     APP + SIGNATURE_MISMATCH  同AppKey 10min≥50次 → 1小时
     APP + RATE_LIMIT_EXCEEDED 持续命中限流>5min  → 30分钟
     IP  + IP_NOT_ALLOWED      同IP 5min≥30次    → 1小时
     APP + MANUAL              人工触发          → 自定义
   ⚠ 生产默认 blocklist.auto.enabled = false，4 条 auto 规则 enabled=false
     理由：避免 NAT / 共享出口误伤
     灰度路径：参数配置开启 → 观察告警曲线 → 再逐步打开单条规则

⑦ 解封
   到期自动解封（ttlSeconds 到期，Redis key 过期即失效）
   人工解封：**必须填解封原因**，记录 unblockBy / unblockReason / unblockTime
   **永久封禁（ttlSeconds=0）解封需审批**
   解封后 status=已解封(0)，**记录不删除**

⑧ 复盘
   blockTimes 累计次数用于识别惯犯与规则调优
   已解封记录保留（如"确认为误报，NAT 共享出口"）
```

**关键约束**
- 自动封禁默认全关，这是与现有 `perm-breach-auto-ban: false` 一致的设计哲学，不要改默认
- 封禁数据结构从"仅 IP"扩展到 IP/CIDR/AppKey 三态，网关 `IpBanCheckHandler` 需相应改造（先查精确 IP，再查 CIDR 网段，再查 AppKey）（推断顺序）
- 解封留痕不可省略——这是"内部人滥用封禁"的制衡点

---

## 六、数据权限与审计模型

### 6.1 数据权限维度

四个维度，全部可多选，**配置为空 = 该维度不限**（原型原话"不限则留空"）：

| 维度 | 字段 | 作用表 | 说明 |
|---|---|---|---|
| 业务线 | `line_id` | `app`/`api_interface`/`api_group` | 第一维度，最常用 |
| 环境 | `env_code` | `app_credential`/`app_quota`/`app_ip_whitelist`/`api_env_config`/`grant`/`api_call_log` | 隔离 dev/test/pre/prod |
| 接口分组 | `group_id` | `api_interface` → 反查 `api_id` | 精细化到分组 |
| 应用归属 | `owner` = 当前用户 | `app` | 用于「仅本人」口径（`API_CONSUMER`/`EXTERNAL_PM`） |

### 6.2 优先级与合并规则（多角色并存时）

用户可拥有多个角色。裁决规则如下（**第 4 条为推断，其余取自原型与既定语义**）：

1. **先并集后收窄**：取用户所有角色的数据范围做**并集**（宽口径），不是交集。理由：权限叠加不应导致用户反而看不到数据。
2. **最宽优先**：若任一角色 `dataScope = 全部数据`（如 ADMIN/AUDITOR），则该用户全量可见，跳过后续所有收窄。
3. **逐维度 AND**：
   ```sql
   WHERE line_id   IN (:可见业务线)            -- 空集合 = 不限
     AND env_code  IN (:可见环境)              -- 空集合 = 不限
     AND (group_id IN (:可见分组) OR :分组为空)  -- 空集合 = 不限
   ```
4. **应用维度追加**（推断）：若角色含 `API_CONSUMER` 或 `EXTERNAL_PM`，额外追加 `app.owner = :当前用户 OR app.id IN (本人关联应用)`，与上述条件 AND。
5. **接口维度推导**：`api_interface` 通过 `group_id` 或 `line_id` 命中上述条件即可见。
6. **调用日志**：`app_id ∈ 可见应用 AND api_id ∈ 可见接口 AND env_code ∈ 可见环境`。
7. **授权记录**：`app_id ∈ 可见应用 AND api_id ∈ 可见接口`。
8. **冲突兜底**：若并集计算后某维度为空集合且其他角色明确配置了值，以"有明确配置的并集"为准；全部为空则该维度不限。

### 6.3 强制位置（安全边界）

| 层 | 措施 |
|---|---|
| **服务端（唯一安全边界）** | MyBatis 拦截器，对 `app`/`api_interface`/`api_group`/`app_api_grant`/`api_call_log`/`app_credential`/`alarm_record` 自动追加过滤条件。原型原话：**"数据权限在服务端强制（MyBatis 拦截器自动拼 SQL 条件），前端隐藏菜单只是体验优化，不是安全边界"** |
| 网关/开放接口 | 不适用（数据权限只约束管理端） |
| 导出 | 导出任务必须携带发起人的数据范围条件，导出结果受同等过滤 |
| 前端 | 菜单不渲染无权限项、按钮不渲染无权限操作、列表字段不返回（如 Secret 掩码） |
| 缓存 | 权限缓存 key 需包含 `userId + activeRoleId`，角色权限变更时**清除该角色下所有在线用户的权限缓存** |

### 6.4 审计日志覆盖范围

**覆盖对象**：控制面（管理端）所有写操作 + 登录行为；数据面（网关调用）走 `api_call_log`，不进审计表。

| 模块 `module` | 覆盖操作 `operateType` |
|---|---|
| `APP` | 创建应用、编辑、启停/注销、创建密钥、重置 Secret、吊销密钥、配额调整、IP 白名单变更 |
| `API` | 创建接口、编辑、参数定义变更、**发布**、**下线**、环境配置变更、版本变更 |
| `GRANT` | 新增授权、**审批通过/驳回**、**撤销**、批量授权、调整配额/有效期 |
| `ROLE` | 角色权限点变更、数据权限变更、角色创建/编辑/删除、用户角色分配 |
| `SYSTEM` | 参数配置变更、**安全策略变更**、字典变更、告警规则变更、通知渠道变更、日志设置变更 |
| `AUTH` | 登录成功、**登录失败**、登出、切换视角（推断） |

**记录字段**：`operatorName`/`operateIp`/`module`/`operateType`/`permCode`/`riskFlag`/`objectDesc`/`changeContent`(JSON)/`result`/`failReason`/`createTime`

**高危标记（riskFlag=1）**：命中 11 个高危权限点之一即置 1——`app:disable`、`app:credential:create`、`app:credential:reset`、`app:credential:revoke`、`api:publish`、`api:offline`、`grant:revoke`、`sys:user:update`、`sys:role:grant`、`sys:security:update`、`audit:export`

**强制约束**
- 高危操作**不可关闭审计**
- 高危操作必须：二次确认 + 填写原因（安全策略 ≥10 字符、吊销密钥 ≥5 字符）+ **实时通知所有平台管理员**
- **只追加、禁删改**：应用使用的数据库账号对审计表**只有 INSERT 权限，无 UPDATE/DELETE 权限**（等保三级要求）
- 失败操作同样记录（`result=0` + `failReason`）

### 6.5 留存期

| 数据类型 | 留存 | 依据 |
|---|---|---|
| 操作审计日志 | **≥ 180 天**（默认 180） | 等保三级要求 ≥180 天（`audit.log.retention.days=180`）。归档：每月 1 日迁移超期记录 |
| 调用日志（热） | 30 天（默认，`call.log.hot.days=30`） | MySQL 保留，超时归档 |
| 调用日志（冷） | 180 天（默认，`sys-log` 表单 coldDays=180） | 对象存储/ClickHouse |
| 接口变更历史 `api_change_log` | 永久保留（推断，体量小） | 接口资产治理需要 |
| 封禁记录 | 永久保留（解封后 status=0 不删除） | 复盘与规则调优 |

> ⚠ **口径冲突待决**：现有 `gatekeeper.log.retention-days: 90`（单值、单表、到期删除）与原型"热 30 + 冷 180"不一致。**建议统一为热 30 → 冷 180 → 删除（总计 180 天）**，P2 落地按天分表后再切换，P0/P1 期间沿用现有 90 天保留。

---

## 七、非功能需求

### 7.1 性能

| 项 | 指标 | 来源 |
|---|---|---|
| 网关鉴权链路开销 | **P99 < 15ms** | 原型「环境与网关」页 |
| 端到端调用延迟 | P95 < 200ms | 现有指标体系（`docs/项目梳理-产品视角.md`） |
| 责任链顺序原则 | 先验廉价条件（时间戳/Nonce/AppKey/状态，内存或 O(1) Redis）→ 昂贵签名计算（HMAC-SHA256 恒定时间比较）→ 配额消耗**必须在身份确认之后** | 原型原话 + 现有实现一致 ✓ |
| 限流实现 | Redis + Lua 令牌桶，O(1) | 现有 + 原型 |
| 概览/大屏 | **禁止直查 `api_call_log` 原始表，必须走预聚合统计表**（每 5 分钟聚合一次） | 原型原话 |
| 授权矩阵 | 接口列建议 ≤ 50 条；批量查走 `(app_id, api_id, env_code)` 唯一索引，禁止 N+1 | 设计约束 |
| 调用日志检索 | P95 < 3s；超过阈值应切 ClickHouse/ES，**而不是继续优化 MySQL** | 原型原话 |
| 日志写入 | 异步，不阻塞主链路（`gateway.log-async: true`） | 现有 ✓ |
| ⌘K 全局搜索 | 接口/应用走本地缓存，日志走 `trace_id` 唯一索引；每类 LIMIT 10 | 设计约束 |

### 7.2 容量

| 项 | 规格 |
|---|---|
| 调用日志量级 | 原型按**日均 5000 万**设计："单表 30 天就是 15 亿行，MySQL 单表完全不可行" |
| 分表 | 必须按天分表 `api_call_log_yyyyMMdd` + 异步写入 |
| 定时任务 | 调用日志建表（每日 01:00）· 调用日志归档（每日 04:00）· 审计日志归档（每月 1 日）· 统计数据聚合（每 5 分钟） |
| 异步导出 | 沿用现有：百万级分批流式 + 临时文件原子改名 + 有界线程池（`CallerRunsPolicy` 背压）+ `export.max.rows=50000` |
| ⚠ 现状差距 | 现有为**单表 `api_call_log`** + 90 天清理。P0/P1 沿用（现状体量可兜底），**P2 必须做按天分表**，触发条件：单表破千万行或检索 P95 > 3s |

### 7.3 安全

| 域 | 要求 |
|---|---|
| **签名** | HmacSHA256（可配 HmacSHA512）。`StringToSign = HTTPMethod\nRequestPath\nCanonicalQueryString\nAppKey\nTimestamp\nNonce\nBodySHA256Hex\n`（每行含末行末尾也有 `\n`）；`Signature = Base64(HMAC-SHA256(StringToSign, Secret))`。请求头：`X-Api-AppKey`/`X-Api-Timestamp`/`X-Api-Nonce`/`X-Api-Signature`。**恒定时间比较**防时序攻击 |
| **重放防护** | 时间戳容差 ±5 分钟（300000ms，内存计算零成本）；Nonce 有效期 600 秒（**应 ≥ 2 倍时间戳容差**），Redis SETNX |
| **密钥存储** | Secret 用 **AES-256-GCM 可逆加密**落库（因服务端需原始 Secret 重新计算签名比对，**不能用 BCrypt 等单向哈希**）。**主密钥由 KMS 或环境变量注入，绝不入库**（现有 `GATEKEEPER_AES_KEY` 已满足，`SecurityStartupCheck` 强制校验长度 ≥32 + 非历史默认值） |
| **密钥生命周期** | `secret.length=32`；`key.max.valid.days=365`（到期自动失效）；`key.rotate.period=180`（超期在概览页告警 + KEY_EXPIRE 告警）；**Secret 仅创建时展示一次**，落库只存掩码 `Yk3m****J5sU`；多套并存平滑轮换 |
| **传输安全** | 接口可声明 `transportSecurity`：NONE 明文允许/TLS 强制 HTTPS/MTLS 双向证书（**平台不强制，接口自愿声明**）。HTTPS 证书到期监控（`CERT_EXPIRE` 告警） |
| **脱敏** | `log.desensitize=true`。脱敏字段：手机号/身份证号/银行卡号/邮箱/姓名。**脱敏在日志写入时生效，已写入的历史日志不追溯处理**。**Secret、签名等凭证字段永远不写入日志**（原型：请求头展示 `X-Api-Signature: K8fJ****（已脱敏）`、AppKey 展示 `ak_prod_7f3a****d605`）。手机号列表即脱敏 `138****0001` |
| **外部应用** | `external.ip.whitelist.required=true`：`appType=2` 强制配置 IP 白名单，为空则红色告警且不应启用 |
| **管理端** | `login.fail.threshold=5`（锁定）；`session.timeout=480` 分钟；**权限点校验（29 个点，11 个高危）**——现有仅校验登录态，这是本次最大安全补齐 |
| **XFF** | `gatekeeper.security.trust-xff: false`（默认不信任 X-Forwarded-For，仅部署在可信代理后置 true） |
| **审计** | 只追加禁删改；应用 DB 账号对审计表**仅 INSERT 权限** |

### 7.4 可用性

| 项 | 要求 |
|---|---|
| **Redis 降级** | **沿用现有 fail-open**：限流/封禁/统计等防护组件在 Redis 故障时放行而非阻断，防护失效不拖垮业务链路。保留 Redis 健康监控与告警 |
| 网关异常 | `GatewayCore` 内部异常 → 推送 CRITICAL 告警（现有能力） |
| 应急开关 | `gateway.auth.enabled`（关闭等于裸奔，**仅应急临时关闭**，修改走高危流程并通知所有管理员）；`gateway.ratelimit.enabled` |
| 自动封禁 | `blocklist.auto.enabled=false` 默认关闭，避免误伤；灰度开启后再观察 |
| 启动自检 | 沿用 `SecurityStartupCheck`：密钥存在性 + 长度 ≥32 + 非历史默认值黑名单，不达标拒绝启动（端口开启前） |
| 配置热更新 | `sys_config` 变更后需广播清除缓存（建议改走配置中心式读取，推断）；启动密钥不入库、仍走环境变量 |
| 部署 | 沿用 Docker Compose（mysql/redis/backend/frontend + 健康检查 + 初始化建表）；新增 17 张表需补充 `init.sql` |

### 7.5 兼容与迁移

| 项 | 要求 |
|---|---|
| 现有 18 表 | **只加列/加表，不删表不改语义**（除 `app_secret` 迁 `app_credential`、`backend_url` 迁 `api_env_config`） |
| 网关链路 | 不推翻，仅 `AppAuthHandler` 内部拆分 5 子阶段用于记录 `reject_stage` |
| 前端 | Vue 2.7 + Element UI 保持不变，按原型重排版式与页面 |
| 数据迁移脚本 | ① `app.app_key/app_secret` → `app_credential`（env=prod，alias="迁移-主密钥"）<br>② `api_interface.backend_url/timeout_ms` → `api_env_config`（env=prod，configStatus=1）<br>③ `ip_ban` → `blocklist`（scope=IP，target=ip_address）<br>④ `app_rate_limit` → `app_quota`（env=prod，monthly_quota=0）<br>⑤ `alert`/`security_event` → `alarm_record`（映射 handle_status）<br>⑥ `api_group` 补 `group_code`（推断：用 `group_` + id 或拼音，需确认）<br>⑦ `app` 补 `app_code`（推断：用现有 `app_key` 或生成） |
| 唯一键变更 | `app_api_permission` 唯一键 `(app_id, interface_id)` → `(app_id, interface_id, env_code)`；存量数据 env 默认 `prod` |

---

## 八、分期建议

**总原则**：现有已稳定运行的能力（网关链路、5 类安全检测、异步导出、Redis fail-open）**不推翻重做**，只做补齐与融合。先建数据底座与主链路闭环，再补治理与可观测，最后做精细化。

### P0 · 「能用」—— 资产底座 + 主链路闭环（预计 5~6 周）

**目标**：让接口/应用/授权三块资产**带环境、带版本、带参数**地跑起来，覆盖日常 80% 的操作。

**页面清单（10 个）**

| 模块 | 页面 | 交付内容 |
|---|---|---|
| 概览 | `dashboard` 概览 | 5 指标卡（复用现有大屏 5 接口）+ 🆕待办聚合 + 🆕风险提示 + 双趋势 + Top 榜单 |
| 应用管理 | `app-list` 应用列表 | 列表 + 筛选 + 🆕详情抽屉（基本信息/密钥/授权/配额/IP/审计 6 Tab）+ 🆕多套凭证创建与吊销 |
| 接口管理 | `api-group` 接口分组 | 列表 + 新建/编辑（含 lineId、groupCode） |
| 接口管理 | `api-list` 接口列表 | 列表 + 筛选 + 🆕详情抽屉（基本信息/参数定义/环境配置/授权情况 4 Tab） |
| 权限管理 | `perm-matrix` 接口授权总览 | 🆕矩阵视图 + 列表视图 + 按环境切换 + 批量授权 + 导出 |
| 权限管理 | `perm-user` 用户管理 | 列表 + 编辑（含工号/部门/业务线/多角色） |
| 系统设置 | `sys-bizline` 业务线管理 | CRUD |
| 系统设置 | `sys-env` 环境与网关 | 环境 CRUD + 网关全局配置 + 鉴权链路说明（静态） |
| 系统设置 | `sys-dict` 字典管理 | 6 个内置字典 + 字典项 CRUD |
| 监控与审计 | `mon-calllog` 调用日志 | 列表（🆕加 traceId/env/错误码/**拦截阶段**）+ 筛选 + 🆕链路详情抽屉（三段耗时）+ 导出 |

**能力清单**

- 🆕 新建表 8 张：`biz_line`、`env`、`app_credential`、`api_param`、`api_env_config`、`sys_dict`、`sys_dict_item`、+ `api_change_log`（仅写入不展示，P1 展示）
- 🔧 改造表 6 张（各表加列明细见 §三 B/C/D）：`app`、`api_group`、`api_interface`、`app_ip_whitelist`、`app_rate_limit`→`app_quota`、`app_api_permission`→授权表（唯一键加 `env_code`）
- 🔧 `api_call_log` 加 `trace_id`/`env_code`/`error_code`/`reject_stage`/`auth_cost`/`upstream_cost`；`AppAuthHandler` 拆分 5 子阶段记录拒绝原因
- 🔧 `sys_user` 加 `emp_no`/`dept`/`line_id`
- ✅ 沿用：网关责任链、5 类安全检测、异步导出、Redis fail-open、Knife4j、Docker Compose
- 🆕 数据迁移脚本（7 条，见 7.5）
- 审批流：**字段与状态机就位，但 `approval.enabled=false`**（提交即生效，仅记录申请人）——与原型配置默认值一致

**验收标准**

1. 能新建应用 → 创建 prod/test 两套凭证 → 用 test 凭证调网关成功，用 prod 凭证调同一接口返回 403 ACL（环境隔离生效）
2. 能为一个接口定义参数（含 JSON 导入生成）、配置 4 套环境地址、做连通测试并置为"已验证"
3. 授权矩阵能按环境切换，10 秒内回答"哪些外部应用能调核心订单接口"
4. 调用日志能按 `rejectStage` 筛选，任意一条失败日志能在详情抽屉看到"被哪道防线拦下"+ 三段耗时
5. 现有 91 个单测全绿 + 新增用例覆盖：多凭证鉴权、环境隔离 ACL、rejectStage 记录、配额双层校验
6. 存量数据迁移后，现有应用在 prod 环境调用行为**无任何变化**（回归验证）

---

### P1 · 「好管」—— 治理与可观测（预计 4~5 周，可与 P0 部分并行）

**页面清单（9 个）**

| 模块 | 页面 | 交付内容 |
|---|---|---|
| 权限管理 | `perm-role` 角色管理 | 🆕 29 权限点树授权（高危红标 + 保存清缓存 + 高危通知）+ 数据权限入口 |
| 权限管理 | `perm-datascope` 数据权限 | 🆕 三维配置 + 效果预览 + **MyBatis 拦截器服务端强制** |
| 权限管理 | `perm-audit` 操作审计 | 列表 + 筛选（高危/模块）+ 导出；`sys_operation_log` 加 risk_flag/perm_code/object_desc/change_content/result/fail_reason |
| 系统设置 | `sys-security` 安全策略 | 11 条配置 + 🆕变更原因 ≥10 字 + 二次确认 + 通知所有管理员 + 签名规范速查 |
| 系统设置 | `sys-config` 参数配置 | 🆕 `sys_config` 表 + 4 分组 18 条 + 运行时热更新 |
| 系统设置 | `sys-alarm` 告警规则 | 🆕 `alarm_rule` + 8 种类型（+ 4 种承接现有安全检测）+ 阈值/窗口/级别/静默期/渠道/接收人 |
| 系统设置 | `sys-notify` 通知渠道 | 🆕 `notify_channel` + 4 种发送器 + 连通性测试 |
| 监控与审计 | `mon-alarm` 告警记录 | `alert`/`security_event` 统一为 `alarm_record`（+rule_id/scope_desc/trigger_value/handler，4 态）+ 🆕一键封禁联动 |
| 监控与审计 | `mon-block` 封禁管理 | `ip_ban` → `blocklist`（scope IP/APP/PLATFORM + reasonCode + ttl + blockTimes + 解封留痕）+ 🆕 5 条 block_rule（默认全关） |

**能力清单**

- 🆕 新建表 7 张：`sys_menu`、`sys_role_menu`、`sys_role_data_scope`、`sys_config`、`alarm_rule`、`alarm_rule_channel/receiver`、`notify_channel`
- 🔧 改造表 3 张：`sys_role`（+role_type/data_scope）、`sys_operation_log`（+6 字段）、`ip_ban`→`blocklist`、`alert`→`alarm_record`
- 🆕 拦截器升级：`JwtAuthInterceptor` 从"校验登录态"→"校验 permCode"（29 点，11 高危）
- 🆕 MyBatis 数据权限拦截器（覆盖 7 张表）
- 🆕 告警定时聚合任务（按 timeWindow 统计）+ 多渠道投递 + 静默期
- 🆕 接口变更历史 `api_change_log` 页面展示（接口详情第 7 个 Tab）

**验收标准**

1. 权限点生效：API_CONSUMER 登录后看不到"发布接口"按钮，且**直接调接口返回 403 无权限**（前端隐藏不是唯一防线）
2. 数据权限生效：BIZ_ADMIN（支付业务线）在应用列表只看到支付业务线的应用；抓包确认服务端返回数据已过滤（不是前端过滤）
3. 高危操作：修改 `sign.timestamp.tolerance` 未填原因 → 拒绝；填了 → 成功 + 审计记录 riskFlag=1 + 所有平台管理员收到通知
4. 告警闭环：造一条失败率超限 → 生成告警 → 企微收到 → 处理填备注 → 状态转"已处理"
5. 一键封禁：从告警封禁某 IP → 该 IP 请求立即被拦（返回封禁错误）→ 解封填原因 → 恢复且记录留痕
6. 权限变更清缓存：修改角色权限后，该角色在线用户无需重新登录即生效

---

### P2 · 「精细」—— 审批流、灰度与容量（预计 4~6 周，按需启动）

**页面清单（1 个新页 + 3 处内嵌增强）**

| 模块 | 页面 | 交付内容 |
|---|---|---|
| 系统设置 | `sys-log` 日志与审计设置 | 🆕 保留期（热/冷/审计）+ 脱敏规则 + 4 个定时任务清单 |
| 权限管理 | `perm-matrix`（内嵌） | 🆕 **授权审批流**：待审批 → 通过/驳回，审批人路由，`approval.enabled=true` |
| 接口管理 | `api-list`（内嵌） | 🆕 版本管理 Tab 的**灰度路由执行**（按 appId/traceId 哈希，grayRatio 生效）+ 变更记录 Tab + 在线文档 Tab + Swagger 导入 |
| 全局 | 顶栏 | 🆕 ⌘K 全局搜索（4 类结果 + 权限过滤） |

**能力清单**

- 🆕 审批流：审批人路由（接口负责人 → 业务线管理员 → 平台管理员）+ 待办通知 + 概览页"待审批授权"跳转
- 🆕 版本灰度路由执行（P0/P1 只记录 grayRatio，不实际路由）
- 🆕 调用日志**按天分表** `api_call_log_yyyyMMdd` + 建表/归档定时任务 + 冷存储（触发条件：单表破千万行或检索 P95 > 3s）
- 🆕 在线文档渲染 + Swagger/OpenAPI 导入
- 🆕 全局搜索（P1 先做简版，P2 完整）
- 🔧 网关链路顺序对齐原型（IP 白名单移到签名之后、认证拆 5 子阶段）——低风险低收益，按需
- 🔧 环境维度引入后，Redis key 加 `env_code` 前缀（限流/封禁/统计）

**验收标准**

1. 审批流：开启 `approval.enabled=true` 后，API_CONSUMER 提交授权 → 状态"待审批" → 接口负责人收到待办 → 通过后"已生效"；驳回后"已驳回"且调用返回 403
2. 灰度：v2 设置 grayRatio=10% → 压测 1000 次，约 10% 流量路由到 v2 上游（±3% 容差）
3. 分表：日志表按天自动创建，30 天前数据自动归档，检索 P95 < 3s
4. 全局搜索：⌘K 聚焦 → 输入"订单" → 接口/应用分类展示 → Enter 跳转详情；越权对象不返回

---

### 分期总览

| 期 | 页面数 | 新建表 | 改造表 | 核心交付价值 |
|---|---|---|---|---|
| P0 | 10 | 8 | 6 | 接口/应用/授权三块资产带环境、带参数、带版本地跑起来 |
| P1 | 9 | 7 | 3 | 权限点 + 数据权限 + 告警闭环 + 封禁治理，安全与可观测补齐 |
| P2 | 1 + 3 内嵌 | 0（+ 分表） | 2 | 审批流、灰度、容量分层、搜索 |
| **合计** | **20** | **15** | **11** | **35 张表（18 现有 + 17 新建）** |

> 说明：新建表总数 17 张，其中 `api_change_log`、`alarm_rule_channel`、`alarm_rule_receiver`、`block_rule` 4 张按上表归入各期的子项（P0 写入不展示、P1 随告警规则/封禁管理落地），故分期表新建数合计为 15（另 2 张 `block_rule` 计入 P1 封禁改造）。

---

## 九、待确认事项（Open Questions）

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| ~~Q1~~ | ~~现有 `app.status`、`api_interface.status`、`alert.status` 的具体枚举语义？~~ | ~~数据迁移正确性~~ | ✅ **已复核**。⚠ 但发现**严重映射冲突**（现有 1=启用 vs 原型 1=待审核），**这是迁移的最高风险项** |
| Q2 | 现有 `api_group.parent_id`（树形）与原型扁平分组冲突，是否保留？ | B1 接口分组 | 建议保留字段 P0 不启用，P2 再决定是否支持二级分组 |
| Q3 | 数据权限多角色并集 vs 交集（6.2 第 1 条为推断） | 权限模型 | 建议并集（宽口径），需与研发/安全确认 |
| ~~Q4~~ | ~~`api_param.paramType=2` 是否存在（MOCK 中缺 2）？~~ | ~~参数定义~~ | ✅ **已解决**：确认存在，`2 = Query 参数`（`paramTypeText` 映射数组明确含 `'Query参数'`，仅 MOCK 未出现该值） |
| Q5 | 灰度路由的执行位置与哈希键（`appId` vs `traceId`） | P2 灰度 | 建议 `appId` 哈希（同应用流量稳定），需研发确认 |
| Q6 | 调用日志留存口径：现有 90 天 vs 原型热 30 + 冷 180 | 容量规划 | 建议统一为总 180 天（热 30 + 冷 150），P2 分表后切换 |
| Q7 | `sys_config` 热更新如何穿透 Spring `@Value` 缓存？ | P1 参数配置 | 建议改走配置中心式读取（每次从缓存读，变更时广播清除） |
| Q8 | 现有 5 类安全检测（权限越界/异常入参/高频调用/异常时段）如何映射为 `alarm_type`？原型 8 种未覆盖 | P1 告警规则 | 建议新增 4 种 `PERM_BREACH`/`ABNORMAL_PARAM`/`HIGH_FREQ`/`OFF_HOURS` |
| Q9 | 存量 `api_group`/`app` 的 `group_code`/`app_code` 如何生成？ | 数据迁移 | 需业务确认命名规则（推断：拼音/现有 key 派生） |
| Q10 | 告警接收人"各应用负责人"/"各接口负责人"是动态解析还是静态用户列表？ | P1 告警规则 | 建议动态解析（按 `app.owner`/`api.owner` 实时展开） |
| Q11 | 「视角切换」是否需要写审计日志？ | 审计模型 | 建议写（module=ROLE, operateType=SWITCH, riskFlag=0） |
| Q12 | 是否需要保留现有 `security_rule` 与新增 `block_rule` 两套规则？ | P1 封禁 | 建议并存（`security_rule` = 安全检测触发，`block_rule` = 封禁触发），但在 UI 上明确区分 |

---

