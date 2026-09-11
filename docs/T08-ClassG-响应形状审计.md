# T08 · Class G（静默空列表）响应形状审计报告

- 审计人：eng-shape（只读静态分析）
- 范围：`src/backend/src/main/java/com/gatekeeper/**`（含 `controller/` `alarm/` `block/` `grant/` 等全部子包）× `src/frontend/src/**`
- 复核命令已内联，可 1:1 重跑。
- 本轮**不改任何代码、不 commit**，仅出审计报告。

---

## 0. 结论摘要（先看这里）

| 项 | 数量 |
|---|---|
| 🔴 **不匹配（静默空列表，需修复）** | **0 处** |
| 🟡 存疑 / 潜在陷阱（非当前故障） | 3 处 |
| ✅ 形状匹配（活代码） | 16 处 `.records/.total` 站点 + 17 处裸数组站点 |
| ⚪ 死代码（形状正确但不可达） | 11 个 view |

> **类 G 在本仓当前不存在「未修复」实例。** 已知的 2 处（`SysNotify.vue`、`SysAlarm.vue#loadChannels`）即该类**全部**历史实例，且均已修复。全仓 39 个 `.records/.total` 取值点**无一**指向裸数组端点；反向（分页端点被当裸数组 `res.data` 用）也为 **0**。

**不匹配处 = 0；分布页面 = 无；最严重前 5 条 = 无 🔴（见 §6 的三条 🟡 擦边风险）。**
「分页组件页面优先」的排序规则见 §4——因 🔴 为 0，该排序仅用于给 🟡 定级。

---

## 1. 方法与判定纪律（可复核）

1. **前端取值点**：`grep -rnE "\.records|data\.total" src/frontend/src` → 25 文件 39 行（含 `SysNotify.vue:135` 的注释）。本仓前端确有**多种取值写法**，逐点区分：
   - ① 裸取 `res.data`（配裸数组端点）；
   - ② 信封取 `res.data.records` / `res.data.total`（配分页端点）；
   - ③ `(res.data && res.data.records) || res.data || []`（防御式，兜底裸数组）；
   - ④ 交给 `CrudTable.normalize()` 自动识别 `d.list` / `d.records`。
2. **后端真实类型**：**全子包递归** grep `public Result<PageResult<` 与 `public Result<List<`（`Result<PageResult<...>>` = 13 个；`Result<List<...>>` = 30+ 个）。**判定以「被调用方法自身」的返回类型为准，绝不用同控制器其它方法推断**（本仓同一控制器内两类混用，见 §2）。
3. **路径还原**：api 函数 → `api/modules.js` 的 `request.get(...)` → 拼类级 `@RequestMapping` 前缀 → 定位 Controller 方法。
4. **活/死代码**：`router/index.js`（24 路由）+ 对每个 view `grep "import"` 反查引用。

---

## 2. 后端端点形状清单

### 2.A 分页：`Result<PageResult<T>>`（`data = {records, total, ...}`）
| 端点 | 方法 | Controller:行 | 完整返回类型 |
|---|---|---|---|
| /alert/list | GET | AlertController.java:48-49 | Result<PageResult<Alert>> |
| /app/list | GET | AppController.java:57-58 | Result<PageResult<App>> |
| /interface/list | GET | InterfaceController.java:54-55 | Result<PageResult<InterfaceListVo>> |
| /log/list | GET | CallLogController.java:54-55 | Result<PageResult<ApiCallLog>> |
| /log/export/tasks | GET | CallLogController.java:118-119 | Result<PageResult<ExportTask>> |
| /security/ip-ban/list | GET | SecurityController.java:54-55 | Result<PageResult<IpBan>> |
| /security/event/list | GET | SecurityController.java:100-101 | Result<PageResult<SecurityEvent>> |
| /system/user/list | GET | SystemController.java:66-67 | Result<PageResult<SysUser>> |
| /system/operation-log/list | GET | SystemController.java:208-209 | Result<PageResult<SysOperationLog>> |
| /biz-line/list | GET | BizLineController.java:64-65 | Result<PageResult<BizLineDto>> |
| /env/list | GET | EnvController.java:55-56 | Result<PageResult<EnvDto>> |
| /config/list | GET | ConfigController.java:55-56 | Result<PageResult<SysConfig>> |
| /dict/list | GET | DictController.java:62-63 | Result<PageResult<SysDict>> |

> 分页参数名分两派：`pageNum/pageSize`（BizLine/Env/Config）与 `current/size`（其余）。前端**已各自对齐**，无第二类静默失败。

### 2.B 裸数组：`Result<List<T>>`（`data` 为数组，**无** records/total）
| 端点 | 方法 | Controller:行 | 完整返回类型 |
|---|---|---|---|
| /notify-channel/list | GET | NotifyChannelController.java:52-53 | Result<List<NotifyChannel>> |
| /alarm-rule/list | GET | AlarmRuleController.java:48-49 | Result<List<AlarmRule>> |
| /block-rule/list | GET | BlockRuleController.java:51-52 | Result<List<BlockRule>> |
| /api-change-log/list | GET | ApiChangeLogController.java:48-49 | Result<List<ApiChangeLogDto>> |
| /api-env-config/list | GET | ApiEnvConfigController.java:54-55 | Result<List<ApiEnvConfigDto>> |
| /app/{id}/ip-whitelist | GET | AppController.java:131-132 | Result<List<AppIpWhitelist>> |
| /api-version/list | GET | ApiVersionController.java:54-55 | Result<List<ApiVersionDto>> |
| /api-param/list | GET | ApiParamController.java:60-61 | Result<List<ApiParamDto>> |
| /api-param/tree | GET | ApiParamController.java:72-73 | Result<List<ApiParamDto>> |
| /group/list | GET | ApiGroupController.java:44-45 | Result<List<ApiGroup>> |
| /group/tree | GET | ApiGroupController.java:55-56 | Result<List<ApiGroup>> |
| /group/{id}/interfaces | GET | ApiGroupController.java:67-68 | Result<List<ApiInterface>> |
| /app-credential/list | GET | AppCredentialController.java:56-57 | Result<List<AppCredentialDto>> |
| /biz-line/all | GET | BizLineController.java:77-78 | Result<List<BizLineDto>> |
| /env/all | GET | EnvController.java:70-71 | Result<List<EnvDto>> |
| /config/all | GET | ConfigController.java:69-70 | Result<List<SysConfig>> |
| /dict/all | GET | DictController.java:75-76 | Result<List<SysDict>> |
| /dict/{dictCode}/items | GET | DictController.java:94-95 | Result<List<SysDictItem>> |
| /grant/list | GET | GrantController.java:46-47 | Result<List<AppApiGrant>> |
| /grant/pending | GET | GrantController.java:96-97 | Result<List<AppApiGrant>> |
| /permission/list | GET | PermissionController.java:56-57 | Result<List<AppApiGrant>> |
| /permission/pending | GET | PermissionController.java:154-155 | Result<List<AppApiGrant>> |
| /security/rule/list | GET | SecurityController.java:133-134 | Result<List<SecurityRule>> |
| /system/role/list | GET | SystemController.java:152-153 | Result<List<SysRole>> |
| /sys/menu/list | GET | SysMenuController.java:50-51 | Result<List<SysMenu>> |
| /sys/menu/perm-points | GET | SysMenuController.java:58-59 | Result<List<SysMenu>> |
| /sys/role-menu/{roleId} | GET | SysRoleMenuController.java:50-51 | Result<List<Long>> |
| /role-data-scope/roles | GET | DataScopeController.java:52-53 | Result<List<RoleSimpleVo>> |
| /role-data-scope/{roleId} | GET | DataScopeController.java:70-71 | Result<List<SysRoleDataScope>> |
| /dashboard/todo | GET | DashboardController.java:113-114 | Result<List<TodoItemVo>> |
| /dashboard/screen/recent-events | GET | DashboardController.java:99-100 | Result<List<SecurityEvent>> |
| /dashboard/screen/trend | GET | DashboardController.java:66-67 | Result<List<Map<String,Object>>> |
| /dashboard/screen/app-rank | GET | DashboardController.java:77-78 | Result<List<Map<String,Object>>> |
| /dashboard/screen/interface-rank | GET | DashboardController.java:88-89 | Result<List<Map<String,Object>>> |

> **关键提醒**：`SecurityController` 同时含裸数组 `/security/rule/list`（2.B）与分页 `/security/ip-ban/list`、`/security/event/list`（2.A）；`SystemController` 同时含角色裸数组与用户/审计分页；`AppController` 含分页 `/app/list` 与裸数组 `/app/{id}/ip-whitelist`；`ApiGroupController` 全裸数组。**这正是「不能靠同控制器推断」的现场证据。**

---

## 3. 前端调用点逐条对照表

判定图例：✅ 匹配 · 🔴 不匹配（静默空列表）· 🟡 存疑 · ⚪死代码

### 3.A 分页端点消费点（写法②：`res.data.records` / `res.data.total`）
| 前端 调用点 file:line | api 函数 | 端点 | 后端返回 | 前端写法 | 判定 |
|---|---|---|---|---|---|
| monitor/MonAlarm.vue:120,121 | getAlertList | /alert/list | PageResult | `data.records`/`data.total` | ✅ 活 |
| monitor/MonCalllog.vue:150,151 | getLogList | /log/list | PageResult | `data.records`/`data.total` | ✅ 活 |
| monitor/MonCalllog.vue:201 | getExportTasks | /log/export/tasks | PageResult | `data.records` | ✅ 活 |
| monitor/MonBlock.vue:175,176 | getBanList | /security/ip-ban/list | PageResult | `data.records`/`data.total` | ✅ 活 |
| perm/PermUser.vue:97,98 | getUserList | /system/user/list | PageResult | `data.records`/`data.total` | ✅ 活 |
| perm/PermAudit.vue:83,84 | getOperationLogList | /system/operation-log/list | PageResult | `data.records`/`data.total` | ✅ 活 |
| system/SysBizLine.vue:119,120 | getBizLineList | /biz-line/list | PageResult | `data.records`/`data.total` | ✅ 活 |
| system/SysConfig.vue:163,164 | getConfigList | /config/list | PageResult | `data.records`/`data.total` | ✅ 活 |
| system/SysDict.vue:187 | getDictList | /dict/list | PageResult | `data.records` | ✅ 活 |
| system/SysEnv.vue:131,132 | getEnvList | /env/list | PageResult | `data.records`/`data.total` | ✅ 活 |
| system/SysLog.vue:89,90 | getOperationLogList | /system/operation-log/list | PageResult | `data.records`/`data.total` | ✅ 活 |
| system/SysSecurity.vue:159,160 | getEventList | /security/event/list | PageResult | `data.records`/`data.total` | ✅ 活 |
| encryption/Index.vue:160 | getAppList | /app/list | PageResult | `data.records` | ✅ 活 |
| encryption/Index.vue:168 | getInterfaceList | /interface/list | PageResult | `data.records` | ✅ 活 |
| components/layout/HeaderBar.vue:98 | getAlertList | /alert/list | PageResult | `(res.data&&res.data.records)||[]`（写法③，分页也兼容） | ✅ 活 |
| system/SysNotify.vue:135 | getNotifyChannelList | /notify-channel/list | **Result<List> 裸** | 注释（已修为写法①） | ✅ 已修 |

> 写法② 的 `data.records` 在以上分页端点上均能正确取值 → 无一处 🔴。

### 3.B 裸数组端点消费点（写法①：`res.data`）
| 前端 调用点 file:line | api 函数 | 端点 | 后端返回 | 前端写法 | 判定 |
|---|---|---|---|---|---|
| system/SysNotify.vue:138-139 | getNotifyChannelList | /notify-channel/list | Result<List> | `(res&&res.data)||[]` | ✅ 活（已修，范式） |
| system/SysAlarm.vue:115 | getNotifyChannelList | /notify-channel/list | Result<List> | `(res&&res.data)||[]` | ✅ 活（已修，范式） |
| system/SysAlarm.vue:126 | getAlarmRuleList | /alarm-rule/list | Result<List> | `(res&&res.data)||[]` | ✅ 活 |
| monitor/MonBlock.vue:164 | getBlockRuleList | /block-rule/list | Result<List> | `res.data||[]` | ✅ 活 |
| system/SysSecurity.vue:148 | getRuleList | /security/rule/list | Result<List> | `res.data||[]` | ✅ 活 |
| perm/PermMatrix.vue:131 | getGrantList / getGrantPending | /grant/list,/grant/pending | Result<List> | `res.data||[]` | ✅ 活 |
| perm/PermRole.vue:99 | getRoleList | /system/role/list | Result<List> | `res.data||[]` | ✅ 活 |
| perm/PermRole.vue:183 | getMenuList | /sys/menu/list | Result<List> | `res.data||[]` | ✅ 活 |
| perm/PermRole.vue:185 | getRoleMenuIds | /sys/role-menu/{id} | Result<List<Long>> | `res.data||[]` | ✅ 活 |
| perm/DataScope.vue:216 | getDataScopeRoles | /role-data-scope/roles | Result<List> | `res.data||[]` | ✅ 活 |
| perm/DataScope.vue:226 | getDataScopeOptions | /role-data-scope/options | Result<Object> | `res.data||{}` | ✅ 活 |
| perm/DataScope.vue:244 | getDataScopeByRole | /role-data-scope/{id} | Result<List> | `res.data||[]` | ✅ 活 |
| app/tabs/CredentialTab.vue:109 | getAppCredentialList | /app-credential/list | Result<List> | `res.data||[]` | ✅ 活 |
| app/tabs/IpWhitelistTab.vue:78 | getIpWhitelist | /app/{id}/ip-whitelist | Result<List> | `res.data||[]` | ✅ 活 |
| api/tabs/ApiParamTab.vue:123,129 | getApiParamList | /api-param/list | Result<List> | `res.data||[]` | ✅ 活 |
| api/tabs/ApiVersionTab.vue:96 | getApiVersionList | /api-version/list | Result<List> | `res.data||[]` | ✅ 活 |
| api/tabs/ApiEnvConfigTab.vue:127 | getApiEnvConfigList | /api-env-config/list | Result<List> | `res.data||[]` | ✅ 活 |
| api/tabs/ApiChangeLogTab.vue:65 | getApiChangeLogList | /api-change-log/list | Result<List> | `res.data||[]` | ✅ 活 |
| api/ApiList.vue:158 | getGroupList | /group/list | Result<List> | `res.data||[]` | ✅ 活 |
| api/ApiGroup.vue:127,128,142 | getGroupTree/getGroupList | /group/tree,/group/list | Result<List> | `res.data||[]` | ✅ 活 |
| system/SysDict.vue:212 | getDictItems | /dict/{code}/items | Result<List> | `res.data||[]` | ✅ 活 |
| dashboard/Dashboard.vue:147 | getDashboardTodo | /dashboard/todo | Result<List> | `res.data||[]` | ✅ 活 |
| dashboard/Dashboard.vue:163,175,186 | getScreenTrend/getAppRank/getInterfaceRank | /dashboard/screen/* | Result<List> | `res.data||[]` | ✅ 活 |

### 3.C 通用容器 `CrudTable`（写法④：自动识别）
| 位置 | 说明 | 判定 |
|---|---|---|
| components/common/CrudTable.vue:154-163 `normalize()` | 支持 `res.list` / `res.data.list` / `res.data.records` / `res.records` | ✅ 见 §6-② 潜在陷阱 |
| app/AppList.vue:136 / api/ApiList.vue:174 | `return getAppList(q)` / `return getInterfaceList(q)` 直接传**原始响应**给 CrudTable | ✅（两端点均为分页，`normalize` 走 `d.records` 命中） |

### 3.D 裸数组取值点但页面为死代码（⚪ 形状✅，不可达，勿混入工单）
| 前端 调用点 file:line | 端点 | 后端返回 | 判定 |
|---|---|---|---|
| app/Index.vue:159 | /app/{id}/ip-whitelist | Result<List> | ✅⚪ |
| security/Rule.vue:124 | /security/rule/list | Result<List> | ✅⚪ |
| system/Index.vue:236 | /system/role/list | Result<List> | ✅⚪ |
| permission/Index.vue:92,104,119 | /permission/list,/group/tree,/group/{id}/interfaces | Result<List> | ✅⚪ |
| interface/Index.vue:151,152 | /group/tree | Result<List> | ✅⚪ |

---

## 4. 数量与严重度

- 🔴 **不匹配（静默空列表）= 0 处**，无页面分布。
- 「分页组件页面优先」排序（本仓所有列表页都带 `el-pagination`，故该规则对 🔴 无对象；仅用于对 🟡 定级）：`PermUser > PermAudit > SysBizLine > SysConfig > SysDict > SysEnv > SysLog > SysSecurity > MonAlarm > MonCalllog > MonBlock > encryption/Index`。
- 类 G 唯一真实发作史：`SysNotify.vue#fetchData`（通知渠道列表）与 `SysAlarm.vue#loadChannels`（告警规则弹窗渠道下拉）——均属**裸数组端点被按信封解析**，已修复。前者症状为「列表恒空」，后者更重：**无法给告警规则配置接收渠道**。

---

## 5. 已修范式（可作 Class G 修复模板）

```js
// ✅ 裸数组端点：直接取 res.data，再兜底空数组
const res = await getNotifyChannelList(rest)
const list = (res && res.data) || []

// ✅ 分页端点：取信封字段
const res = await getAlertList(query)
this.list  = res.data.records
this.total = res.data.total
```
反模式（类 G 触发点）：`const list = res.data.records || []` 用在**裸数组**端点上 → `undefined || []` → 恒空且不抛错。

---

## 6. 存疑 / 潜在陷阱（🟡，非当前故障）

1. **`components/common/RoutePreview.vue:104`**（活，`ApiEnvConfigTab` 引用）—— 端点 `/api-env-config/list` 为**裸数组**，该行写法③ `(res.data && res.data.records) || res.data || []`：`.records` 恒 undefined，靠 `|| res.data` 兜底**当前能工作**；但它是**唯一**在活代码里「对裸端点触碰 `.records`」的站点，属擦边。若后端改为分页、或有人删掉 `|| res.data`，立即退化为静默空列表。**建议**：改为写法①以消除歧义。
2. **`components/common/CrudTable.vue:154-163` `normalize()`** —— 仅识别 `d.list` / `d.records` / `res.list` / `res.records` 四类；对**原始裸数组响应**（`res.data` 本身是数组，且未被外层 fetch 拆包）**无兜底**，会返回 `{list:[],total:0}`（静默空）。当前唯一直接 `return getXxx()` 的 `AppList`/`ApiList` 均传**分页**端点，故未触发。属**潜在陷阱**：任何 view 若把裸数组端点直接 `return` 给 CrudTable 即静默空表。**建议**：在 `normalize` 追加 `if (Array.isArray(d)) return {list:d, total:d.length}`。
3. **字段名勘误（非形状问题）**：`common/Result.java` 实为 `{code, message, data}`（字段 `message`，非 `msg`）；前端拦截器 `api/index.js:36-38` 读的正是 `res.message`，一致，无缺陷。仅备注以免后续文档继续写 `msg`。

---

## 7. 附录 · 死代码 view 清单（无路由、无 import 引用）

`views/alert/Index.vue`、`views/app/Index.vue`、`views/interface/Index.vue`、`views/log/Index.vue`、`views/permission/Index.vue`、`views/system/Index.vue`、`views/dashboard/Index.vue`、`views/security/Ban.vue`、`views/security/Event.vue`、`views/security/Rule.vue`、`components/Layout.vue`（另 `views/StubPage.vue` 已显式标注预留）。
> 复核：`router/index.js` 无对应路由；`grep -rn "views/<dir>" src/frontend/src` 无 import 命中。其形状虽 ✅，但**均不可达，不纳入工单**。功能已由 MonAlarm / AppList / ApiList·ApiGroup / MonCalllog / PermMatrix / PermUser·PermRole·PermAudit·SysLog / Dashboard / MonBlock / SysSecurity 承接。

---

## 8. 给 T08 的裁定建议

- 若按「是否存在未修复的静默空列表」判：**Class G 当前无未修复实例**，可不并网修复；
- 若按「消除擦边隐患」判：建议仅做 2 个**低风险**改动（§6-① RoutePreview 改写法①；§6-② CrudTable.normalize 补数组兜底），二者均属「防御性收敛」，不改变任何现有正确行为。

---

## 9. 裁定与移交（team-lead T08 决议 · 就地追加）

### 9.1 结论与价值定位
- **本轮不并入 T08 收口**：真实不匹配 = **0**，无用户可见缺陷，不产生修复工单。
- 本轮产出价值 = **回归确认 + 方法留档**（确认 Class G 已闭合、沉淀判定纪律），**不是修复**。

### 9.2 擦边 2 处：留 T09，且**禁止顺手修**
> 下列 2 处本轮**不动**；「为何不动」须随行保留，否则下轮会被「顺手修」而引入回归。

- **① `src/frontend/src/components/common/RoutePreview.vue:104`**
  - 现状：`(res.data && res.data.records) || res.data || []` 对端点 `/api-env-config/list`（**裸数组**）靠 `|| res.data` 兜底，**当前可工作**。
  - **为何能侥幸工作**：该接口恰好返回裸数组、无 `records` 信封 ⇒ 回落到 `res.data`。
  - **为何排后**：其触发路径 `load()`（含 104 行）仅在 `autoLoad=true` 时执行；当前唯一调用方 `api/tabs/ApiEnvConfigTab.vue:3` 以 `:envs="envCards"` 传入、**未开 `autoLoad`** ⇒ **该行在当前路由链路下不执行**，属**非主链路**预览构件（与 team-lead「非主链路」定性一致，理由已用源码坐实）。
  - **建议（T09）**：改为写法① `const list = (res && res.data) || []` 消除歧义。**低风险**，因不在主链路而排后。

- **② `src/frontend/src/components/common/CrudTable.vue` 的 `normalize()`（第 154-163 行）**
  - 现状：仅认 `d.list` / `d.records` / `res.list` / `res.records`，**缺「`res.data` 本身即裸数组」分支** ⇒ 对未被外层拆包的裸数组响应返回 `{ list: [], total: 0 }`（第 163 行，静默空）。
  - **⚠️ 高风险共享组件纪律**：`CrudTable` 为**全仓所有表格共用**，改 `normalize()` 波及**每一个列表页**。**必须独立一轮 + 独立回归验证；禁止与其他改动混批**（不得搭车进任何无关修复）。
  - **建议（T09）**：单独补 `if (Array.isArray(d)) return { list: d, total: d.length }`，随后跑**全列表页**回归。

### 9.3 明确写入结论的一句话
> **「0 不匹配 ≠ 无隐患」**：形状适配层对**非数组一律兜底成 `[]`**，使该类缺陷**运行时不可见** —— 空数组不崩、不报错，只静默渲染空列表（本仓可验证的兜底点即 `CrudTable.vue:155` 与 `:163` 的 `return { list: [], total: 0 }`）。
> 因此本报告 §0 的「0 处」**只能**被引用为「截至本次审计的静态结论」，**不得**被后续轮次引用为「此处已安全、无需再看」。形状核查须以**带数据的正面断言 `total > 0 && list.length > 0`** 为准；「不报错 / 返回不崩 / 不是空数组」均**不构成**通过证据。

### 9.4 方法纪律落到具体落点（不留「建议带回」空话）
- **成文位置（首选）**：并入 `docs/T05-前端实施计划.md` 的「验证」章节，新增子节**「响应形状核验（Class G 判据）」**；
- **或**直接作为 **T09 的验收判据**（推荐：前者成文 + 后者引用）。判据内容：
  1. 判形状**不得只读形状声明（如 `shape.js` 的 `'array'`），必须核控制器真实返回类型**（本仓同一控制器内两类混用，见 §2）；
  2. **「不是空数组」是无效证明**；唯一有效证明是**带数据的正面断言** `total > 0 && list.length > 0`；
  3. 每个前端取值点须归入 §1 的 4 种写法之一，**禁止以单一 grep 下结论**。

### 9.5 事实核对（避免后续引用错路径）
§9.2 的文件路径以**实际源码树 `src/frontend/src`** 为准。team-lead 复述中出现的下列**路径/符号在当前工作树中未找到**（已全仓 `grep -rn` + `glob **/*.js|**/*.vue` 核验；仅命中 `docs/T08-权限执行缺口-契约记录.md` 自身文本）：
- `views/prototype/RoutePreview.vue` → 实际为 `components/common/RoutePreview.vue`（本仓**无** `views/prototype/` 目录）；
- `shape.js`、`filterToFrontend`、`role.js`、`pickList`、`CrudTable.vue:107`（`utils/` 仅 `dict.js`/`perm.js`/`enum.js`；`CrudTable.vue:107` 位于 JSDoc 注释内）。
> 若上述符号属 T09 拟引入的形状适配层或其它分支，请在 T09 中**重新指向真实文件与行号**；在此之前一律以本报告 §2/§3 的实证行号为准。
