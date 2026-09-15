-- =====================================================================
-- T15-6：移除「业务线」维度（存量库清理脚本，幂等 · 可重复执行）
-- ---------------------------------------------------------------------
-- 背景
--   需求：「目前业务线业务好像用不到，删除」。
--   业务线（biz_line）本是一级组织维度，被 app / api_interface / api_group /
--   sys_user 的 line_id 列引用，并作为数据权限（sys_role_datascope.scope_type）
--   的三个维度之一（BIZ_LINE / ENV / API_GROUP）。
--   T15 决策：**下线业务线功能模块**（CRUD + 菜单 + 权限点 + 数据权限维度 + 前端页面），
--   但遵守演进式重构铁律「只加列不删列 / 只加表不删表」：
--     · `biz_line` 表**保留**（不 DROP）
--     · `app` / `api_interface` / `api_group` / `sys_user` 的 `line_id` 列**保留**（不 ALTER DROP）
--     · `sys_role_datascope` 表与 `scope_type='BIZ_LINE'` 枚举值**保留**（历史行兼容）
--   仅移除「权限点 / 菜单 / 角色授权」这些会出现在 UI 与鉴权链路上的活数据。
--
-- 执行顺序
--   必须在**所有**种子脚本之后执行（init.sql / t02 / t03a / t03b / t07a / t08 /
--   migrate-v2 / t13 …），因为它做的是「减法」；放在前面会被后续 INSERT 重新播种。
--   ⚠️ 已知会重新播种业务线权限点的脚本：init.sql（sys_menu id 71-74 + id 212）。
--   T15 已同步从 init.sql 删除这些种子行，本脚本用于**修复已建库**。
--
-- 执行后必做
--   DEL gk:perm:*      -- 权限缓存优先 TTL 24h，不清缓存会「看着没生效」
-- =====================================================================

USE `gatekeeper`;

-- ---------------------------------------------------------------------
-- 1) 先删子表：角色→菜单授权（避免留下悬空 menu_id）
-- ---------------------------------------------------------------------
-- 1.1 按已知 id 精确删除
DELETE FROM `sys_role_menu`
 WHERE `menu_id` IN (71, 72, 73, 74, 212);

-- 1.2 兜底：按权限码 / 路由 / 菜单名语义删除（覆盖其它脚本另行插入的新 id）
DELETE `srm` FROM `sys_role_menu` `srm`
  JOIN `sys_menu` `sm` ON `sm`.`id` = `srm`.`menu_id`
 WHERE `sm`.`perm_code` LIKE 'biz\_line:%'
    OR `sm`.`route_path` = '/sys/bizline'
    OR (`sm`.`type` = 2 AND `sm`.`name` = '业务线管理');

-- ---------------------------------------------------------------------
-- 2) 再删菜单与权限点本体
-- ---------------------------------------------------------------------
DELETE FROM `sys_menu`
 WHERE `perm_code` LIKE 'biz\_line:%'
    OR `route_path` = '/sys/bizline'
    OR (`type` = 2 AND `name` = '业务线管理');

-- ---------------------------------------------------------------------
-- 3) 清理指向已不存在业务线的悬挂绑定
--    注：`app` / `api_interface` / `api_group` 的 line_id 当前全为 NULL，
--        仅 `sys_user.line_id` 带历史迁移值（默认业务线 100），置空即可。
-- ---------------------------------------------------------------------
UPDATE `sys_user` SET `line_id` = NULL WHERE `line_id` IS NOT NULL;

-- ---------------------------------------------------------------------
-- 4) 自查（应全部返回 0）
-- ---------------------------------------------------------------------
SELECT 'sys_menu 残留业务线节点' AS chk, COUNT(*) AS cnt
  FROM `sys_menu`
 WHERE `perm_code` LIKE 'biz\_line:%' OR `route_path` = '/sys/bizline'
UNION ALL
SELECT 'sys_role_menu 悬空授权', COUNT(*)
  FROM `sys_role_menu` `srm`
  LEFT JOIN `sys_menu` `sm` ON `sm`.`id` = `srm`.`menu_id`
 WHERE `sm`.`id` IS NULL
UNION ALL
SELECT 'sys_role_datascope BIZ_LINE 行（允许保留）', COUNT(*)
  FROM `sys_role_datascope`
 WHERE `scope_type` = 'BIZ_LINE';
