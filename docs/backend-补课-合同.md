# 后端补课合同：Config / Dict / DataScope 三个控制器

> **目的**：本文件是一份「可直接交给后端工程师（eng-t01）实现」的精确合同，用于补齐 T05 前端计划的阻塞项——`sys-config`、`sys-dict`、`perm-datascope` 三个页面**没有后端 API**。
>
> **状态**：4 张表（`sys_config` / `sys_dict` / `sys_dict_item` / `sys_role_datascope`）与 4 个实体类已存在于代码中（来自 `schema-v2.sql`），**无需新增任何 DB 列**。缺的是 `Controller` / `Service` / `Mapper` 三层 + 少量 DTO。
>
> **范围**：仅本 3 个控制器。不修改任何既有后端代码、不改动存量表结构。
>
> **语言**：Java 8 / Spring Boot 2.7 / MyBatis-Plus 3.5，与项目既有 `BizLineController` 等 CRUD 三件组完全一致。

---

## 0. 阻塞项回顾（来自 `docs/T05-前端实施计划.md` §8）

| 前端页面 | route | 依赖表 | 现状 |
|---|---|---|---|
| `sys-config` 参数配置 | `/sys/sys-config` | `sys_config` | 实体存在，**无 Controller** |
| `sys-dict` 字典管理 | `/sys/sys-dict` | `sys_dict` + `sys_dict_item` | 实体存在，**无 Controller** |
| `perm-datascope` 数据权限 | `/perm/perm-datascope` | `sys_role_datascope` | 实体存在，**无 Controller** |

---

## 1. 实施约束与既有约定（必读，全部对齐 `BizLineController`）

1. **Context-path**：所有路径相对 `/api`。实际访问 = `http://localhost:8080/api/<RequestMapping><path>`。
2. **响应包装**：
   - 分页：`Result<PageResult<T>>`，其中 `PageResult{records,total,current,size}`。
   - 非分页集合：`Result<List<T>>`。
   - 单对象：`Result<T>`；写操作成功返回 `Result<Void>`（`Result.success()`）。
   - `Result` 静态方法：`success(T)` / `success()` / `error(code,msg)` / `notFound(msg)` / `forbidden()` / `unauthorized()`。
3. **参数风格**：分页用 `@RequestParam(pageNum=1,pageSize=10,keyword,status)`；写操作用 `@Valid @RequestBody XxxDto`；路径参数用 `@PathVariable`。
4. **权限注解约定（对齐 `BizLineController` 第 38–42 行注释）**：
   - 仅对**高危写操作**加 `@RequirePerm`；读操作与低危写操作**不强制**（页面可见性已通过菜单 perm 控制）。
   - 高危 = 删除，或 `risk_flag=1` 的写操作。
   - `@RequirePerm(value="xxx", risk=true/false)` 来自 `com.gatekeeper.security.RequirePerm`。
5. **类注解**：`@RestController` + `@RequestMapping("/xxx")` + `@RequiredArgsConstructor` + `@Tag(name=, description=)`（Swagger v3）。构造器注入 `private final XxxService`。
6. **复用清单（已存在，勿新建）**：

| 实体类 | 包 | 说明 |
|---|---|---|
| `SysConfig` | `com.gatekeeper.entity` | 字段见 §3.4；`sensitive` 列用 `@TableField("`sensitive`")` 反引号映射（MySQL 保留字） |
| `SysDict` | `com.gatekeeper.entity` | 字段见 §4.4 |
| `SysDictItem` | `com.gatekeeper.entity` | 字段见 §4.4 |
| `SysRoleDataScope` | `com.gatekeeper.entity` | 字段见 §5.4 |
| `Result` / `PageResult` | `com.gatekeeper.common` | 响应包装 |
| `RequirePerm` | `com.gatekeeper.security` | 权限注解 |
| `BizLineMapper` / `EnvMapper` / `ApiGroupMapper` | `com.gatekeeper.mapper` | DataScope 选择器/选项复用 |

---

## 2. 权限点对齐（permCode → 真实 `sys_menu` 种子）

### 2.1 真实种子核对（来源 `docs/sql/migrate-v2.sql` 第 103–120 行）

| permCode | id | 名称 | risk | 是否本 3 控制器需要 |
|---|---|---|---|---|
| `sys:config:list` | 51 | 查看配置 | 0 | ✅ Config 读 |
| `sys:config:update` | 52 | 修改配置 | 0 | ✅ Config 写/删 |
| `sys:dict:update` | 54 | 字典管理 | 0 | ✅ Dict 全部操作（无细分 view/create/delete 种子） |
| `sys:datascope:view` | — | **不存在** | — | ❌ **前端 router 期望，但无种子（关键缺口）** |
| `sys:datascope:update` | — | **不存在** | — | ❌ **需补种（写操作网关）** |

> 页面节点 `sys-dict`(id 213)、`sys-config`(id 216)、`perm-datascope`(id 208) 的 `perm_code` 均为 **NULL**——页面可见性 = 该模块下用户是否拥有任一 type=3 权限点（见 migrate-v2.sql 第 122–124 行注释）。

### 2.2 9 个推断 permCode 的归属结论（team-lead 任务 #3）

Task 1 §7 推断了 9 个 permCode。经与真实 `sys_menu` 种子核对，**仅 1 个落入本 3 控制器范围**，且未播种：

| 推断 permCode（Task1 §7） | 归属页面 | 属本 3 控制器？ | 真实种子 | 处置 |
|---|---|---|---|---|
| `sys:datascope:view` | perm-datascope | ✅ 是 | ❌ 无 | **必须补种**（见 §5.5）+ 配套 `sys:datascope:update` |
| `sys:config:list` | sys-config | ✅ 是 | ✅ id51 | 直接复用 |
| `sys:dict:update` | sys-dict | ✅ 是 | ✅ id54 | 直接复用（兼作 view/create/delete 网关） |
| `sys:user:list` | perm-user | ❌ 否 | — | 超范围（该页后端已齐） |
| `sys:role:list` | perm-role | ❌ 否 | — | 超范围 |
| `audit:list` | perm-audit | ❌ 否 | — | 超范围 |
| `sys:env:list` | sys-env | ❌ 否 | EnvController 已有 | 超范围 |
| `sys:security:view` | sys-security | ❌ 否 | 仅 `sys:security:update`(id53) | 超范围 |
| `sys:bizline:list` | sys-bizline | ❌ 否 | BizLineController 用 `biz_line:*` | 超范围 |
| `sys:notify:list` | sys-notify | ❌ 否 | NotifyChannelController 已有 | 超范围 |
| `sys:log:list` | sys-log | ❌ 否 | `log:call:list`(id61) | 超范围 |

**结论**：config / dict 两控制器可直接复用既有种子；**datascope 必须补 2 个种子**（`sys:datascope:view` 读、`sys:datascope:update` 写，risk=1），否则前端页面因 `menu.js` 校验 `sys:datascope:view` 而永远不可见。补种 SQL 见 §5.5。

---

## 3. 控制器 1：ConfigController（sys-config 参数配置）

### 3.1 基本信息
- **包名**：`com.gatekeeper.controller`
- **@RequestMapping**：`/config`
- **类注解**：`@RestController` `@RequestMapping("/config")` `@RequiredArgsConstructor` `@Tag(name="参数配置", description="系统参数配置管理")`
- **字段脱敏**：`sensitive=1` 的配置，`config_value` 在响应中返回 `"******"`（前端不展示明文，见 §7）。

### 3.2 完整接口清单

| # | 方法 | 路径 | 入参 | 响应形状 | permCode | @RequirePerm | 说明 |
|---|---|---|---|---|---|---|---|
| C1 | GET | `/config/list` | `pageNum=1,pageSize=10,keyword,configGroup,status` | `Result<PageResult<SysConfig>>` | `sys:config:list` | 否 | 分页；`configGroup`/`status` 可空 |
| C2 | GET | `/config/all` | `configGroup`(可空) | `Result<List<SysConfig>>` | `sys:config:list` | 否 | 全量/下拉 |
| C3 | GET | `/config/{id}` | `@PathVariable Long id` | `Result<SysConfig>` | `sys:config:list` | 否 | 详情；`sensitive=1` 时脱敏 |
| C4 | POST | `/config/create` | `@Valid @RequestBody SysConfigDto` | `Result<SysConfig>` | `sys:config:update` | `value="sys:config:update"` | 唯一键 `config_key`(uk_config_key)，冲突报 `error("配置键已存在")` |
| C5 | PUT | `/config/update` | `@Valid @RequestBody SysConfigDto` | `Result<Void>` | `sys:config:update` | `value="sys:config:update"` | id 必填 |
| C6 | DELETE | `/config/{id}` | `@PathVariable Long id` | `Result<Void>` | `sys:config:update` | `value="sys:config:update"` | `built_in=1` 拒绝删除，返回 `error("内置配置不可删除")` |

### 3.3 Service 接口（`com.gatekeeper.service.ConfigService`）
```java
public interface ConfigService extends IService<SysConfig> {
    PageResult<SysConfig> pageQuery(int pageNum, int pageSize, String keyword, String configGroup, Integer status);
    List<SysConfig> listAll(String configGroup);          // configGroup 可空
    SysConfig getConfig(Long id);                          // 不存在返回 null（Controller 判 notFound）
    SysConfig createConfig(SysConfigDto dto);              // 校验 config_key 唯一
    void updateConfig(SysConfigDto dto);
    void deleteConfig(Long id);                            // 内置保护在 Service 层抛 BizException
}
```
- 实现类 `ConfigServiceImpl` 放于 `com.gatekeeper.service.impl`。

### 3.4 Mapper / 实体复用
- **新建** `com.gatekeeper.mapper.SysConfigMapper`：`public interface SysConfigMapper extends BaseMapper<SysConfig>`（`@Mapper`）。
- **复用实体** `SysConfig`（`com.gatekeeper.entity`）。字段（与 `schema-v2.sql` 第 374–388 行一致）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 主键 AUTO |
| configKey | String | 配置键，唯一(uvk_config_key) |
| configValue | String | 配置值 |
| configGroup | String | SECURITY/GATEWAY/LOG/DEFAULT |
| configName | String | 配置名称 |
| `sensitive` | Integer | **反引号列** `@TableField("`sensitive`")`，1=脱敏 |
| builtIn | Integer | 1=内置不可删 |
| remark | String | 备注 |
| createdAt / updatedAt | LocalDateTime | 自动填充 |

### 3.5 DTO（`com.gatekeeper.dto.SysConfigDto`）
```java
@Data
public class SysConfigDto {
    private Long id;                                  // 创建时空，更新必填
    @NotBlank(message="配置键不能为空")
    @Size(max=128) private String configKey;
    @Size(max=1024) private String configValue;
    @NotBlank(message="配置分组不能为空")
    @Size(max=32) private String configGroup;
    @Size(max=128) private String configName;
    private Integer sensitive;                       // 0/1
    private Integer builtIn;                         // 仅内置种子用，前端一般不传
    @Size(max=512) private String remark;
}
```

### 3.6 缺失 DB 列
**无。** 所有字段已存在 `schema-v2.sql`。

---

## 4. 控制器 2：DictController（sys-dict 字典管理）

### 4.1 基本信息
- **包名**：`com.gatekeeper.controller`
- **@RequestMapping**：`/dict`
- **类注解**：`@RestController` `@RequestMapping("/dict")` `@RequiredArgsConstructor` `@Tag(name="数据字典", description="数据字典与字典项管理")`

### 4.2 完整接口清单

| # | 方法 | 路径 | 入参 | 响应形状 | permCode | @RequirePerm | 说明 |
|---|---|---|---|---|---|---|---|
| D1 | GET | `/dict/list` | `pageNum=1,pageSize=10,keyword,status` | `Result<PageResult<SysDict>>` | `sys:dict:update` | 否 | 字典分页 |
| D2 | GET | `/dict/all` | — | `Result<List<SysDict>>` | `sys:dict:update` | 否 | 下拉 |
| D3 | GET | `/dict/{dictCode}` | `@PathVariable String dictCode` | `Result<SysDictDetailVo>` | `sys:dict:update` | 否 | 字典+项 详情 |
| D4 | GET | `/dict/{dictCode}/items` | `@PathVariable String dictCode` | `Result<List<SysDictItem>>` | `sys:dict:update` | 否 | 项列表 |
| D5 | POST | `/dict/create` | `@Valid @RequestBody SysDictDto` | `Result<SysDict>` | `sys:dict:update` | `value="sys:dict:update"` | 唯一键 `dict_code`(uk_dict_code) |
| D6 | PUT | `/dict/update` | `@Valid @RequestBody SysDictDto` | `Result<Void>` | `sys:dict:update` | `value="sys:dict:update"` | dictCode 必填 |
| D7 | DELETE | `/dict/{id}` | `@PathVariable Long id` | `Result<Void>` | `sys:dict:update` | `value="sys:dict:update"` | `built_in=1` 拒绝删除 |
| D8 | POST | `/dict/{dictCode}/items` | `@RequestBody SysDictItemDto` | `Result<SysDictItem>` | `sys:dict:update` | `value="sys:dict:update"` | 新增字典项（原型「新增字典项」弹窗 480px） |
| D9 | PUT | `/dict/items/{itemId}` | `@RequestBody SysDictItemDto` | `Result<Void>` | `sys:dict:update` | `value="sys:dict:update"` | 改项 |
| D10 | DELETE | `/dict/items/{itemId}` | `@PathVariable Long itemId` | `Result<Void>` | `sys:dict:update` | `value="sys:dict:update"` | 删项 |

> 说明：原型 `page-sys-dict` 的「新增字典项」弹窗是对**单个字典**追加项，故项 CRUD 挂在 `/dict/{dictCode}/items` 与 `/dict/items/{itemId}` 下。字典头（dictCode/dictName）与项（items）分开维护，避免一次 PUT 全量替换丢失并发。

### 4.3 Service 接口（`com.gatekeeper.service.DictService`）
```java
public interface DictService extends IService<SysDict> {
    PageResult<SysDict> pageQuery(int pageNum, int pageSize, String keyword, Integer status);
    List<SysDict> listAll();
    SysDictDetailVo getWithItems(String dictCode);     // 含 items
    List<SysDictItem> listItems(String dictCode);
    SysDict createDict(SysDictDto dto);                // 校验 dict_code 唯一
    void updateDict(SysDictDto dto);
    void deleteDict(Long id);                          // 内置保护
    SysDictItem addItem(SysDictItemDto dto);           // 校验 (dict_code,item_value) 唯一
    void updateItem(SysDictItemDto dto);
    void deleteItem(Long itemId);
}
```
- `SysDictDetailVo` = `{ SysDict dict; List<SysDictItem> items; }`（VO 放 `com.gatekeeper.vo`）。
- 实现类 `DictServiceImpl` 放于 `com.gatekeeper.service.impl`。

### 4.4 Mapper / 实体复用
- **新建** `SysDictMapper extends BaseMapper<SysDict>`、`SysDictItemMapper extends BaseMapper<SysDictItem>`（`@Mapper`）。
- **复用实体**：
  - `SysDict`：`id, dictCode(String 唯一), dictName(String), builtIn(Integer), status(Integer 0停用/1启用), remark, createdAt, updatedAt`。
  - `SysDictItem`：`id, dictCode(String FK→sys_dict.dict_code), itemValue(String), itemLabel(String), sortOrder(Integer), status(Integer)`。

### 4.5 DTO（`com.gatekeeper.dto`）
```java
@Data
public class SysDictDto {
    private Long id;
    @NotBlank @Size(max=64) private String dictCode;
    @NotBlank @Size(max=128) private String dictName;
    private Integer status;
    @Size(max=255) private String remark;
    // items 不随字典头一起提交；项通过 D8/D9 单独维护
}

@Data
public class SysDictItemDto {
    private Long id;
    @NotBlank @Size(max=64) private String dictCode;   // 归属字典
    @NotBlank @Size(max=64) private String itemValue;
    @NotBlank @Size(max=128) private String itemLabel;
    private Integer sortOrder;
    private Integer status;
}
```

### 4.6 缺失 DB 列
**无。** 字段已存在 `schema-v2.sql` 第 393–419 行。

---

## 5. 控制器 3：DataScopeController（perm-datascope 数据权限）

### 5.1 基本信息
- **包名**：`com.gatekeeper.controller`
- **@RequestMapping**：`/role-data-scope`（后端无模块前缀，对齐 `/env` `/notify-channel` 风格；前端将按此路径联通）
- **类注解**：`@RestController` `@RequestMapping("/role-data-scope")` `@RequiredArgsConstructor` `@Tag(name="数据权限", description="角色数据范围配置")`
- **语义**：`sys_role_datascope` 空表 = 该角色**不限范围**（见 schema-v2.sql 第 136–149 行注释）。`PUT /{roleId}` 为**全量覆盖**。

### 5.2 完整接口清单

| # | 方法 | 路径 | 入参 | 响应形状 | permCode | @RequirePerm | 说明 |
|---|---|---|---|---|---|---|---|
| S1 | GET | `/role-data-scope/roles` | `keyword`(可空) | `Result<List<RoleSimpleVo>>` | `sys:datascope:view` | 否 | 角色选择器（roleId,roleName,dataScope） |
| S2 | GET | `/role-data-scope/options` | — | `Result<DataScopeOptionsVo>` | `sys:datascope:view` | 否 | 范围值选项：bizLines/envs/apiGroups |
| S3 | GET | `/role-data-scope/{roleId}` | `@PathVariable Long roleId` | `Result<List<SysRoleDataScope>>` | `sys:datascope:view` | 否 | 某角色当前范围集合（空=不限） |
| S4 | PUT | `/role-data-scope/{roleId}` | `@Valid @RequestBody RoleDataScopeSaveDto` | `Result<Void>` | `sys:datascope:update` | `value="sys:datascope:update", risk=true` | 高危写：全量覆盖该角色范围 |

> 原型 `page-perm-datascope` 还有「效果预览」，`S2/S3` 的数据足以支撑前端预览（展示该角色可见的业务线/环境/分组）。预览为纯前端计算，无需额外接口。

### 5.3 Service 接口（`com.gatekeeper.service.RoleDataScopeService`）
```java
public interface RoleDataScopeService {
    List<RoleSimpleVo> listRolesForSelector(String keyword);   // 复用 SysRoleMapper
    DataScopeOptionsVo listOptions();                          // 查 biz_line / env / api_group
    List<SysRoleDataScope> listByRole(Long roleId);
    void saveRoleScopes(RoleDataScopeSaveDto dto);              // 删除旧 + 批量插入新
}
```
- 实现类 `RoleDataScopeServiceImpl` 放于 `com.gatekeeper.service.impl`。
- `saveRoleScopes` 用 `@Transactional`：先 `DELETE FROM sys_role_datascope WHERE role_id=?`，再批量 `INSERT` dto.scopes。`scopes` 为空列表 = 不限。
- `RoleSimpleVo` = `{ Long roleId; String roleName; String dataScope; }`（dataScope 取 `sys_role.data_scope`：ALL/BIZ_LINE/CUSTOM/SELF）。
- `DataScopeOptionsVo` = `{ List<BizLineSimple> bizLines; List<EnvSimple> envs; List<ApiGroupSimple> apiGroups; }`，字段取 `id/lineCode/lineName`、`envCode/envName`、`id/groupCode/groupName`。

### 5.4 Mapper / 实体复用
- **新建** `SysRoleDataScopeMapper extends BaseMapper<SysRoleDataScope>`（`@Mapper`）。
- **复用实体** `SysRoleDataScope`：`id, roleId(Long), scopeType(String BIZ_LINE/ENV/API_GROUP), scopeValue(String 业务线ID/环境编码/分组ID), createdAt`。
- **复用 Mapper**（已存在）：`SysRoleMapper`（查角色）、`BizLineMapper`（options）、`EnvMapper`（options）、`ApiGroupMapper`（options）。

### 5.5 ⚠️ 必须补种的 `sys_menu` 种子 + 角色授权（关键缺口）

前端 `src/frontend/src/router/menu.js:25` 校验 `perm: 'sys:datascope:view'`，但**真实种子里没有这个 permCode**（migrate-v2.sql 第 103–120 行无对应行）。**不补种则 perm-datascope 页面永远不可见、接口 403。** 请后端在 `migrate-v2.sql` 或独立补种脚本中加入：

```sql
-- 数据权限 · 权限点（pid=4 权限管理模块，紧接现有 47 之后）
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(48, 4, '数据权限查看', 3, 'sys:datascope:view',  NULL, 0, 8, 1),
(49, 4, '数据权限配置', 3, 'sys:datascope:update', NULL, 1, 9, 1);  -- risk=1 高危

-- 授权给需要管理数据权限的角色（示例：BIZ_ADMIN 与 SECURITY_AUDITOR）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 48 FROM sys_role r WHERE r.role_code IN ('BIZ_ADMIN','SECURITY_AUDITOR');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 49 FROM sys_role r WHERE r.role_code IN ('BIZ_ADMIN','SECURITY_AUDITOR');
```

> 注：`sys_role_data_scope` 表本身已有 seed 数据（migrate-v2.sql 第 205–210 行给 BIZ_ADMIN 预置 ENV/test,prod），无需改动。

### 5.6 DTO（`com.gatekeeper.dto`）
```java
@Data
public class RoleDataScopeSaveDto {
    @NotNull(message="角色ID不能为空") private Long roleId;
    @Valid private List<DataScopeItemDto> scopes = new ArrayList<>();  // 空=不限
}

@Data
public class DataScopeItemDto {
    @NotBlank(message="范围类型不能为空")
    @Pattern(regexp="BIZ_LINE|ENV|API_GROUP", message="scopeType 必须为 BIZ_LINE/ENV/API_GROUP")
    private String scopeType;
    @NotBlank(message="范围值不能为空") private String scopeValue;
}
```

### 5.7 缺失 DB 列
**无。** 字段已存在 `schema-v2.sql` 第 140–149 行。

---

## 6. 需新增文件清单

| 层 | 文件 | 包 |
|---|---|---|
| Controller | `ConfigController.java` | `com.gatekeeper.controller` |
| Controller | `DictController.java` | `com.gatekeeper.controller` |
| Controller | `DataScopeController.java` | `com.gatekeeper.controller` |
| Service 接口 | `ConfigService.java` / `DictService.java` / `RoleDataScopeService.java` | `com.gatekeeper.service` |
| Service 实现 | `ConfigServiceImpl.java` / `DictServiceImpl.java` / `RoleDataScopeServiceImpl.java` | `com.gatekeeper.service.impl` |
| Mapper | `SysConfigMapper.java` / `SysDictMapper.java` / `SysDictItemMapper.java` / `SysRoleDataScopeMapper.java` | `com.gatekeeper.mapper` |
| DTO | `SysConfigDto.java` / `SysDictDto.java` / `SysDictItemDto.java` / `RoleDataScopeSaveDto.java` / `DataScopeItemDto.java` | `com.gatekeeper.dto` |
| VO | `SysDictDetailVo.java` / `RoleSimpleVo.java` / `DataScopeOptionsVo.java` | `com.gatekeeper.vo` |
| SQL | 补种脚本（datascope 权限点 + 角色授权） | `docs/sql/` |

**合计**：13 个 Java 类 + 1 个补种 SQL。原 4 个实体类直接复用，0 个 DB 列迁移。

---

## 7. 跨控制器通用规则

1. **Config 脱敏**：`sensitive=1` 时，`config_value` 在 C1/C2/C3 响应中返回固定字符串 `"******"`；写接口仍按明文入参落库（前端录入敏感值时走密文/明文由前端决定，本端不做加解密，仅展示脱敏）。
2. **内置保护**：`sys_config.built_in=1` 与 `sys_dict.built_in=1` 禁止删除（C6/D7 在 Service 层抛业务异常，Controller 转 `Result.error("内置数据不可删除")`）。
3. **字典状态语义**：`sys_dict.status` / `sys_dict_item.status` 均为 `0=停用,1=启用`（对齐原型枚举字典 §一 dicts 行），**不要**套用其它实体 status 语义。
4. **分页契约**：所有 `* /list` 统一 `pageNum/pageSize/keyword`，返回 `Result<PageResult<T>>`，`current` 取 pageNum，`size` 取 pageSize。
5. **空结果**：单对象查询不存在返回 `Result.notFound("xxx不存在")`；列表/分页无数据返回空集合（非 null）。

---

## 8. 风险与待确认项

| # | 项 | 级别 | 说明 / 处置 |
|---|---|---|---|
| R1 | **datascope 缺权限种子** | 🔴 阻塞 | §5.5 必须补种 `sys:datascope:view`+`sys:datascope:update` 并授权角色，否则页面不可见、接口 403 |
| R2 | config/dict 仅有 `update` 类种子 | 🟡 | 无细分 `:create`/`:delete` 种子；本合同统一用写种子网关（与前端 router 一致）。如需更细粒度，后续补种 |
| R3 | `sensitive` 为 MySQL 保留字 | 🟡 | 实体已用 `@TableField("`sensitive`")` 反引号；Mapper XML（若有）勿漏反引号 |
| R4 | DataScope 范围值校验 | 🟡 | `scopeValue` 目前按字符串存储（业务线ID/环境编码/分组ID）；未做外键强校验，建议 Service 层按 scopeType 校验存在性 |
| R5 | 与既有 `@RequirePerm` 切面一致性 | 🟢 | 读操作不强制 perm（对齐 BizLineController 注释）；若安全审计要求读也鉴权，需统一调整切面，不在本合同约定范围 |

---

## 9. 交接 Check-list（eng-t01）

- [ ] 新建 §6 列出的 13 个 Java 类 + 1 个补种 SQL，包名/注解严格对齐 `BizLineController`。
- [ ] 执行 §5.5 补种 SQL（datascope 权限点 + 角色授权），并在测试库验证 `sys:datascope:view` 已落入 `gk:perm:{uid}`。
- [ ] 本地起后端，`POST /api/auth/login` 后，用 BIZ_ADMIN 令牌验证：
  - `GET /api/config/list` 200；`GET /api/dict/list` 200；`GET /api/role-data-scope/roles` 200。
  - `PUT /api/role-data-scope/{roleId}` 写入后 `GET /api/role-data-scope/{roleId}` 返回一致。
  - `DELETE /api/config/{id}` 对 `built_in=1` 返回错误。
- [ ] 不修改任何存量 Controller / 实体 / 表结构；不引入新依赖。
- [ ] 完成后通知前端（software-architect-2 / T05 计划）按本合同路径联通三页面。
