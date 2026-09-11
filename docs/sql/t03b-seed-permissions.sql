-- ============================================================
-- T03b 种子：接口管理域（参数 / 版本 / 环境配置 / 变更历史）新增权限点
-- ============================================================
--
-- 设计前提（与 software-architect-2 的 P0 权限点契约对齐方案协调结论 = Option A）：
--   T03b 4 个子域**沿用既有扁平 REST 控制器**（/api-param、/api-version、
--   /api-env-config、/api-change-log）与既有 `api_*` 权限码，仅**补齐缺失能力**，
--   不新造 `interface_*` 码、不重复播种已存在的码。
--
--   因此本脚本**只播种 3 个真正新增的权限点**，其余 8 个 T03b 涉及的码
--   已由既有种子覆盖，本脚本不再重复 INSERT（详见文末「归属说明」）。
--
-- 幂等：全部走 sys_menu.uk_menu_perm（perm_code 唯一键）+ INSERT IGNORE，
--       重复执行不产生重复行；角色授权走 sys_role_menu(role_id,menu_id) 主键 + INSERT IGNORE。
--
-- ID 段约定（与 architect-2 协调）：
--   本脚本仅使用 **96–199 段**（该段当前完全空闲，DB 实测 ROWS=0），
--   绝不触碰 architect-2 的 **301–341 段**（api:delete / api_param:create 等归属其方案）。
--
-- 应用执行：
--   mysql -h <host> -P <port> -u <user> -p<pass> gatekeeper < docs/sql/t03b-seed-permissions.sql
--
-- 风险标记说明：
--   risk_flag=1 表示「高危操作」，SysOperationLogAspect 会强制写审计日志。
--   本批 3 个码中 2 个高危：
--     - api_param:import    批量导入参数（覆盖式写入，高危）
--     - api_version:gray    版本灰度（影响线上流量分配，高危）
--     - api_env_config:test 连通性探测（只读探测，非高危）
--
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 接口管理域（pid=3 接口管理模块）—— 新增 3 个权限点（2 高危）
-- ---------------------------------------------------------------------------
-- 说明：pid=3 是 migrate-v2.sql 中 id=3 的「接口管理」模块节点（type=1）。
--       api:list(31) / api:create(32) / api:update(33) / api:publish(34) 已存在。
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(101, 3, '导入接口参数',   3, 'api_param:import',    NULL, 1, 8,  1),
(102, 3, '版本灰度发布',   3, 'api_version:gray',    NULL, 1, 9,  1),
(103, 3, '环境连通性测试', 3, 'api_env_config:test', NULL, 0, 10, 1);

-- ---------------------------------------------------------------------------
-- 2) 角色授权（sys_role_menu）—— 按 PRD 角色矩阵分配新增的 3 个权限点
-- ---------------------------------------------------------------------------
-- 角色矩阵（T03b 相关）：
--   ADMIN       平台管理员      → 全部
--   BIZ_ADMIN   业务线管理员    → 全部（管理所辖业务线的接口资产）
--   API_PROVIDER 接口提供方     → 全部（接口参数/版本/环境配置的第一责任人）
--   API_CONSUMER 接口调用方     → 无（不参与接口定义与环境配置）
--   AUDITOR     审计员          → 无（只读审计，见文末说明）
--   EXTERNAL_PM 外部对接人      → 无

-- ADMIN（平台管理员）→ 3 个全给
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'ADMIN'
  AND m.perm_code IN ('api_param:import', 'api_version:gray', 'api_env_config:test');

-- BIZ_ADMIN（业务线管理员）→ 3 个全给
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'BIZ_ADMIN'
  AND m.perm_code IN ('api_param:import', 'api_version:gray', 'api_env_config:test');

-- API_PROVIDER（接口提供方）→ 3 个全给
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'API_PROVIDER'
  AND m.perm_code IN ('api_param:import', 'api_version:gray', 'api_env_config:test');

-- ---------------------------------------------------------------------------
-- 3) 存量 SUPER_ADMIN / OPERATOR / SECURITY_AUDITOR 的双轨映射
--    由 T01 的 sys_role_menu 双轨映射（SUPER_ADMIN←ADMIN、OPERATOR←BIZ_ADMIN、
--    SECURITY_AUDITOR←AUDITOR）自动继承 ADMIN/BIZ_ADMIN 的新权限，无需在本脚本重复插入。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 4) 归属说明：以下 8 个 T03b 涉及的权限码**已存在**，本脚本不重复播种
--    避免与 architect-2 的 seed-perm-alignment.sql（301–341 段）产生主键/唯一键冲突。
-- ---------------------------------------------------------------------------
--   api:list            (id=31,  migrate-v2.sql)            —— 接口列表
--   api:create          (id=32,  migrate-v2.sql)            —— 新增接口
--   api:update          (id=33,  migrate-v2.sql)            —— 编辑接口
--   api:publish         (id=34,  migrate-v2.sql, T02 高危)   —— 发布接口（T03b 复用，未新增）
--   api:delete          (id=311, seed-perm-alignment.sql)   —— 删除接口
--   api_param:create    (id=312, seed-perm-alignment.sql)   —— 新增参数
--   api_param:delete    (id=313, seed-perm-alignment.sql)   —— 删除参数
--   api_version:create  (id=314, seed-perm-alignment.sql)   —— 新建版本
--   api_env_config:create (id=315, seed-perm-alignment.sql) —— 新增环境配置
--   api_env_config:delete (id=316, seed-perm-alignment.sql) —— 删除环境配置
--   api_change_log:append (id=317, seed-perm-alignment.sql) —— 追加变更历史
--   api_param:update    (id=337, seed-perm-alignment.sql)   —— 编辑参数
--
--   说明：@RequirePerm 引用了上述全部码；其中 api:publish / api:delete 等为 T02/T05
--         既有高危点，T03b 直接复用，符合「不重复播种、不推翻既有契约」的铁律。

-- ---------------------------------------------------------------------------
-- 5) 校验：列出 T03b 新增的 3 个权限点及其角色授权
-- ---------------------------------------------------------------------------
-- SELECT id, pid, name, perm_code, risk_flag, sort_order, status
-- FROM sys_menu
-- WHERE perm_code IN ('api_param:import', 'api_version:gray', 'api_env_config:test')
-- ORDER BY id;
--
-- -- 校验角色授权（应各返回 1 行/角色）：
-- SELECT r.role_code, COUNT(*) c
-- FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
-- WHERE m.perm_code IN ('api_param:import', 'api_version:gray', 'api_env_config:test')
-- GROUP BY r.role_code ORDER BY r.role_code;
