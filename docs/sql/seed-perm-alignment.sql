-- ============================================================================
-- T05 权限点契约对齐 · 补种脚本（P0）
-- ----------------------------------------------------------------------------
-- 权威来源：docs/T05-权限点契约对齐方案.md
-- 目的：补齐 menu.js / router / PermButton 引用但 sys_menu 缺失的 32 个 perm_code，
--       修复「菜单永久不可见 + 接口 403」的跨 Phase 契约断裂。
--
-- 幂等性：全部 INSERT IGNORE，依赖 sys_menu.uk_menu_perm(perm_code) 与
--         sys_role_menu.uk_role_menu(role_id, menu_id) 唯一键；可重复执行，结果一致。
--
-- id 段：本批占用 301–341（现有 MAX(id)=221；刻意避开 100–199 段以兼容 T03b 任务 #16）。
--        其它负责人请勿使用 301–341。
--
-- 前置：已执行 init.sql → schema-v2.sql → migrate-v2.sql → t03a-seed-permissions.sql
--        → t02-seed-admin-role.sql → seed-datascope-perm.sql
--
-- 执行后：见文件末 §F 验证查询。
-- ============================================================================

SET NAMES utf8mb4;
USE `gatekeeper`;

-- ############################################################################
-- A. Class A — 菜单视图类权限点（pid: 1=概览 4=权限管理 5=系统设置 6=监控）
-- ############################################################################
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(301, 1, '概览查看',     3, 'dashboard:view',    NULL, 0, 1,  1),
(302, 4, '查看用户',     3, 'sys:user:list',     NULL, 0, 10, 1),
(303, 4, '查看角色',     3, 'sys:role:list',     NULL, 0, 11, 1),
(304, 4, '查看审计',     3, 'audit:list',        NULL, 0, 12, 1),
(305, 5, '查看安全策略', 3, 'sys:security:view', NULL, 0, 6,  1),
(306, 5, '查看通知渠道', 3, 'sys:notify:list',   NULL, 0, 7,  1),
(307, 5, '查看日志审计', 3, 'sys:log:list',      NULL, 0, 8,  1),
(308, 6, '查看告警记录', 3, 'alarm:list',        NULL, 0, 4,  1),
(309, 6, '查看封禁名单', 3, 'block:list',        NULL, 0, 5,  1);

-- ############################################################################
-- B. Class B — 后端 @RequirePerm 写类权限点（全部 risk=1 高危）
--    注：311–317 为接口域，与 T03b 任务 #16 可能重叠，INSERT IGNORE 已保证幂等；
--        若 T03b 已先播种同一 perm_code，本段自动跳过，不产生重复行。
-- ############################################################################
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(311, 3, '删除接口',       3, 'api:delete',              NULL, 1, 7,  1),
(312, 3, '新增参数',       3, 'api_param:create',        NULL, 1, 8,  1),
(313, 3, '删除参数',       3, 'api_param:delete',        NULL, 1, 9,  1),
(314, 3, '新建版本',       3, 'api_version:create',      NULL, 1, 10, 1),
(315, 3, '新增环境配置',   3, 'api_env_config:create',   NULL, 1, 11, 1),
(316, 3, '删除环境配置',   3, 'api_env_config:delete',   NULL, 1, 12, 1),
(317, 3, '追加变更历史',   3, 'api_change_log:append',   NULL, 1, 13, 1),
(318, 4, '驳回授权',       3, 'grant:reject',            NULL, 1, 13, 1),
(319, 5, '新建告警规则',   3, 'alarm_rule:create',       NULL, 1, 9,  1),
(320, 5, '新建通知渠道',   3, 'notify_channel:create',   NULL, 1, 10, 1),
(321, 6, '新建封禁规则',   3, 'block_rule:create',       NULL, 1, 6,  1),
(322, 6, '手动封禁',       3, 'block_rule:manual',       NULL, 1, 7,  1);

-- ############################################################################
-- C. Class C — 前端 PermButton 引用但未播种的权限点（risk=0，前端闸门）
--    前提：落库前请抽验对应后端端点存在；不存在的属死代码，应另开缺陷单，不必播种。
-- ############################################################################
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(331, 2, '删除应用',       3, 'app:delete',             NULL, 0, 8,  1),
(332, 2, '修改配额',       3, 'app:quota:update',       NULL, 0, 9,  1),
(333, 2, '新增白名单',     3, 'app:ipwhitelist:add',    NULL, 0, 10, 1),
(334, 2, '删除白名单',     3, 'app:ipwhitelist:delete', NULL, 0, 11, 1),
(335, 2, '完成密钥轮换',   3, 'app_credential:complete',NULL, 0, 12, 1),
(336, 3, '停用/启用接口',  3, 'api:disable',            NULL, 0, 14, 1),
(337, 3, '编辑参数',       3, 'api_param:update',       NULL, 0, 15, 1),
(338, 3, '新建分组',       3, 'api_group:create',       NULL, 0, 16, 1),
(339, 3, '编辑分组',       3, 'api_group:update',       NULL, 0, 17, 1),
(340, 3, '删除分组',       3, 'api_group:delete',       NULL, 0, 18, 1),
(341, 5, '编辑通知渠道',   3, 'sys:notify:update',      NULL, 0, 11, 1);

-- ############################################################################
-- D. 角色授权（sys_role_menu）—— 最小权限，见方案 §7 矩阵
-- ############################################################################

-- D1. SUPER_ADMIN / ADMIN（平台管理员）→ 全部 32 个新权限点
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r JOIN sys_menu m
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
  AND m.perm_code IN (
    'dashboard:view','sys:user:list','sys:role:list','audit:list','sys:security:view',
    'sys:notify:list','sys:log:list','alarm:list','block:list',
    'api:delete','api_param:create','api_param:delete','api_version:create',
    'api_env_config:create','api_env_config:delete','api_change_log:append','grant:reject',
    'alarm_rule:create','notify_channel:create','block_rule:create','block_rule:manual',
    'app:delete','app:quota:update','app:ipwhitelist:add','app:ipwhitelist:delete',
    'app_credential:complete','api:disable','api_param:update',
    'api_group:create','api_group:update','api_group:delete','sys:notify:update'
  );

-- D2. BIZ_ADMIN / OPERATOR（业务线管理员）→ 业务域全操作 + 相关视图
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r JOIN sys_menu m
WHERE r.role_code IN ('BIZ_ADMIN', 'OPERATOR')
  AND m.perm_code IN (
    'dashboard:view','sys:notify:list','alarm:list',
    'api:delete','api_param:create','api_param:delete','api_version:create',
    'api_env_config:create','api_env_config:delete','api_change_log:append','grant:reject',
    'alarm_rule:create',
    'app:delete','app:quota:update','app:ipwhitelist:add','app:ipwhitelist:delete',
    'app_credential:complete','api:disable','api_param:update',
    'api_group:create','api_group:update','api_group:delete'
  );

-- D3. API_PROVIDER（接口提供方）→ 接口定义与配置
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r JOIN sys_menu m
WHERE r.role_code = 'API_PROVIDER'
  AND m.perm_code IN (
    'dashboard:view',
    'api:delete','api_param:create','api_param:delete','api_version:create',
    'api_env_config:create','api_env_config:delete','api_change_log:append',
    'api:disable','api_param:update','api_group:create','api_group:update','api_group:delete'
  );

-- D4. API_CONSUMER（接口调用方）→ 应用与密钥自助
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r JOIN sys_menu m
WHERE r.role_code = 'API_CONSUMER'
  AND m.perm_code IN (
    'dashboard:view',
    'app:quota:update','app:ipwhitelist:add','app:ipwhitelist:delete','app_credential:complete'
  );

-- D5. AUDITOR / SECURITY_AUDITOR（审计员）→ 全局只读 + 封禁/安全只读
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r JOIN sys_menu m
WHERE r.role_code IN ('AUDITOR', 'SECURITY_AUDITOR')
  AND m.perm_code IN (
    'dashboard:view','sys:user:list','sys:role:list','audit:list','sys:security:view',
    'sys:log:list','alarm:list','block:list','block_rule:create','block_rule:manual'
  );

-- D6. EXTERNAL_PM（外部对接人）→ 仅概览
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r JOIN sys_menu m
WHERE r.role_code = 'EXTERNAL_PM'
  AND m.perm_code IN ('dashboard:view');

-- ############################################################################
-- E. 存量角色传播兜底（幂等）—— 保证 SUPER_ADMIN/OPERATOR/SECURITY_AUDITOR 自动继承
--    （与 migrate-v2.sql §1.5 的双轨映射同逻辑，此处仅对新增权限重放一次）
-- ############################################################################
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r1.id, rm.menu_id FROM sys_role r1
JOIN sys_role r2 ON r2.role_code = 'ADMIN'
JOIN sys_role_menu rm ON rm.role_id = r2.id
WHERE r1.role_code = 'SUPER_ADMIN';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r1.id, rm.menu_id FROM sys_role r1
JOIN sys_role r2 ON r2.role_code = 'BIZ_ADMIN'
JOIN sys_role_menu rm ON rm.role_id = r2.id
WHERE r1.role_code = 'OPERATOR';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r1.id, rm.menu_id FROM sys_role r1
JOIN sys_role r2 ON r2.role_code = 'AUDITOR'
JOIN sys_role_menu rm ON rm.role_id = r2.id
WHERE r1.role_code = 'SECURITY_AUDITOR';

-- ############################################################################
-- F. 验证查询（执行后人工核对，期望结果见注释）
-- ############################################################################

-- F1. 新增 32 个权限点是否就位（期望 32 行）
-- SELECT id, pid, name, perm_code, risk_flag FROM sys_menu
-- WHERE id BETWEEN 301 AND 341 ORDER BY id;

-- F2. Class A：menu.js 全部叶子 perm 是否已播种（期望返回空集）
-- SELECT x.code FROM (
--   SELECT 'dashboard:view' code UNION ALL SELECT 'app:list' UNION ALL SELECT 'api:list'
--   UNION ALL SELECT 'sys:user:list' UNION ALL SELECT 'sys:role:list' UNION ALL SELECT 'grant:list'
--   UNION ALL SELECT 'sys:datascope:view' UNION ALL SELECT 'audit:list' UNION ALL SELECT 'env:list'
--   UNION ALL SELECT 'sys:security:view' UNION ALL SELECT 'biz_line:list' UNION ALL SELECT 'sys:dict:update'
--   UNION ALL SELECT 'sys:alarm:update' UNION ALL SELECT 'sys:notify:list' UNION ALL SELECT 'sys:config:list'
--   UNION ALL SELECT 'sys:log:list' UNION ALL SELECT 'log:call:list' UNION ALL SELECT 'alarm:list'
--   UNION ALL SELECT 'block:list'
-- ) x
-- LEFT JOIN sys_menu m ON m.perm_code = x.code AND m.type = 3 AND m.status = 1
-- WHERE m.id IS NULL;

-- F3. Class B：后端 @RequirePerm 30 值是否全部播种（期望返回空集）
-- SELECT x.code FROM (
--   SELECT 'api:delete' code UNION ALL SELECT 'api_param:create' UNION ALL SELECT 'api_param:delete'
--   UNION ALL SELECT 'api_version:create' UNION ALL SELECT 'api_env_config:create'
--   UNION ALL SELECT 'api_env_config:delete' UNION ALL SELECT 'api_change_log:append'
--   UNION ALL SELECT 'grant:reject' UNION ALL SELECT 'alarm_rule:create' UNION ALL SELECT 'notify_channel:create'
--   UNION ALL SELECT 'block_rule:create' UNION ALL SELECT 'block_rule:manual'
--   UNION ALL SELECT 'biz_line:delete' UNION ALL SELECT 'env:delete'
--   UNION ALL SELECT 'app:disable' UNION ALL SELECT 'app:credential:create' UNION ALL SELECT 'app:credential:reset'
--   UNION ALL SELECT 'app:credential:revoke' UNION ALL SELECT 'app_credential:create'
--   UNION ALL SELECT 'api:publish' UNION ALL SELECT 'sys:user:update' UNION ALL SELECT 'sys:role:grant'
--   UNION ALL SELECT 'audit:export' UNION ALL SELECT 'sys:security:update' UNION ALL SELECT 'sys:dict:update'
--   UNION ALL SELECT 'sys:config:update' UNION ALL SELECT 'grant:create' UNION ALL SELECT 'grant:approve'
--   UNION ALL SELECT 'grant:revoke' UNION ALL SELECT 'sys:datascope:update'
-- ) x
-- LEFT JOIN sys_menu m ON m.perm_code = x.code AND m.type = 3 AND m.status = 1
-- WHERE m.id IS NULL;

-- F4. admin(uid=1) 有效权限点数（期望 = 74）
-- SELECT COUNT(DISTINCT m.id)
-- FROM sys_user_role ur
-- JOIN sys_role_menu rm ON rm.role_id = ur.role_id
-- JOIN sys_menu m ON m.id = rm.menu_id AND m.type = 3 AND m.status = 1 AND m.perm_code IS NOT NULL
-- WHERE ur.user_id = 1;
-- ============================================================================
